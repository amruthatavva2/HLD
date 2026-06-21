package com.typeahead.service.batch;

import com.typeahead.config.AppProperties;
import com.typeahead.service.TrieIndex;
import com.typeahead.service.cache.DistributedCache;
import com.typeahead.service.metrics.MetricsService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Flushes aggregated search counts from the {@link BatchWriteBuffer} to the durable store.
 *
 * <h3>When it flushes</h3>
 * <ul>
 *   <li>Periodically, every {@code typeahead.batch.flush-interval-ms} ({@code @Scheduled}).</li>
 *   <li>Eagerly, once the buffer holds {@code typeahead.batch.size} distinct queries
 *       (checked via {@link #flushIfFull()} from the /search path).</li>
 *   <li>On graceful shutdown, via {@code @PreDestroy}, so a clean stop loses nothing.</li>
 * </ul>
 *
 * <h3>What a flush does</h3>
 * <ol>
 *   <li>Drain the buffer (query -> delta).</li>
 *   <li>Apply the deltas to the in-memory Trie, getting back the new absolute counts.</li>
 *   <li>Write those rows to H2 in a single JDBC batch using {@code MERGE} (upsert).</li>
 *   <li>Invalidate the cache entries for every prefix of every updated query, so stale
 *       suggestion lists do not linger (TTL is only the backstop).</li>
 * </ol>
 *
 * <h3>Failure trade-off</h3>
 * The buffer is in memory, so a hard crash before a flush loses that window's increments
 * (we trade a little durability for far fewer writes). The {@code @PreDestroy} flush covers
 * graceful shutdowns; production options (append-only WAL, durable queue) are discussed in
 * the design doc.
 */
@Component
public class BatchWriter {

    private static final Logger log = LoggerFactory.getLogger(BatchWriter.class);

    private final BatchWriteBuffer buffer;
    private final TrieIndex trie;
    private final DistributedCache cache;
    private final MetricsService metrics;
    private final JdbcTemplate jdbc;
    private final int batchSize;
    private final ReentrantLock flushLock = new ReentrantLock();

    public BatchWriter(BatchWriteBuffer buffer, TrieIndex trie, DistributedCache cache,
                       MetricsService metrics, JdbcTemplate jdbc, AppProperties props) {
        this.buffer = buffer;
        this.trie = trie;
        this.cache = cache;
        this.metrics = metrics;
        this.jdbc = jdbc;
        this.batchSize = props.getBatch().getSize();
    }

    @Scheduled(fixedDelayString = "${typeahead.batch.flush-interval-ms}")
    public void scheduledFlush() {
        flush("interval");
    }

    /** Called from the /search path; flushes immediately once the buffer is full. */
    public void flushIfFull() {
        if (buffer.distinctSize() >= batchSize) {
            flush("size");
        }
    }

    @PreDestroy
    public void flushOnShutdown() {
        flush("shutdown");
    }

    /** Force a flush (used by the admin endpoint / tests). */
    public int flushNow() {
        return flush("manual");
    }

    private int flush(String reason) {
        // tryLock: never let two flushes (scheduled + size-triggered) overlap.
        if (!flushLock.tryLock()) return 0;
        try {
            Map<String, Long> deltas = buffer.drain();
            if (deltas.isEmpty()) return 0;

            Map<String, Long> newCounts = trie.applyDeltas(deltas);
            long now = System.currentTimeMillis();

            List<Object[]> args = new ArrayList<>(newCounts.size());
            for (Map.Entry<String, Long> e : newCounts.entrySet()) {
                args.add(new Object[]{ e.getKey(), e.getValue(), now });
            }
            jdbc.batchUpdate(
                    "MERGE INTO search_query (query, count, last_searched_at) KEY(query) VALUES (?, ?, ?)",
                    args);

            invalidateAffectedPrefixes(deltas.keySet());
            metrics.recordFlush(newCounts.size());
            log.info("Flush[{}]: {} distinct queries written to DB in 1 batch", reason, newCounts.size());
            return newCounts.size();
        } catch (Exception e) {
            log.error("Flush[{}] failed", reason, e);
            return 0;
        } finally {
            flushLock.unlock();
        }
    }

    /** A query affects the cached suggestions of every one of its prefixes. */
    private void invalidateAffectedPrefixes(Iterable<String> queries) {
        for (String q : queries) {
            for (int i = 1; i <= q.length(); i++) {
                cache.invalidate(q.substring(0, i));
            }
            cache.invalidate(q); // also the full string (no-op duplicate for safety)
        }
    }
}
