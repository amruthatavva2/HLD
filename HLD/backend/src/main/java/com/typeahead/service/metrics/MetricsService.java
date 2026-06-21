package com.typeahead.service.metrics;

import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central counters for the performance report:
 * <ul>
 *   <li>cache hits / misses  -> cache hit rate</li>
 *   <li>DB reads / rows written / flush count -> write-reduction evidence</li>
 *   <li>search submissions -> compared against DB writes to show batching savings</li>
 *   <li>a rolling ring-buffer of /suggest latencies -> live p50 / p95</li>
 * </ul>
 * All counters are lock-free (atomics); the latency buffer is a fixed-size ring so
 * memory is bounded regardless of traffic.
 */
@Service
public class MetricsService {

    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong dbReads = new AtomicLong();
    private final AtomicLong dbRowsWritten = new AtomicLong();
    private final AtomicLong flushCount = new AtomicLong();
    private final AtomicLong searchSubmissions = new AtomicLong();

    private static final int LATENCY_RING = 20_000;
    private final long[] latenciesMicros = new long[LATENCY_RING];
    private final AtomicInteger latencyIdx = new AtomicInteger();
    private final AtomicLong latencyTotal = new AtomicLong();

    public void cacheHit() { cacheHits.incrementAndGet(); }
    public void cacheMiss() { cacheMisses.incrementAndGet(); }
    public void addDbReads(long n) { dbReads.addAndGet(n); }
    public void recordFlush(long rowsWritten) {
        flushCount.incrementAndGet();
        dbRowsWritten.addAndGet(rowsWritten);
    }
    public void searchSubmitted() { searchSubmissions.incrementAndGet(); }

    /** Record the server-side latency of a /suggest call. */
    public void recordSuggestLatency(long nanos) {
        long micros = nanos / 1000;
        int i = (int) (Math.floorMod(latencyIdx.getAndIncrement(), LATENCY_RING));
        latenciesMicros[i] = micros;
        latencyTotal.incrementAndGet();
    }

    public long cacheHits() { return cacheHits.get(); }
    public long cacheMisses() { return cacheMisses.get(); }

    public double cacheHitRate() {
        long h = cacheHits.get();
        long total = h + cacheMisses.get();
        return total == 0 ? 0.0 : (double) h / total;
    }

    /** percentile in [0,100]; returns microseconds. */
    public long latencyPercentileMicros(double percentile) {
        long total = latencyTotal.get();
        if (total == 0) return 0;
        int n = (int) Math.min(total, LATENCY_RING);
        long[] copy = Arrays.copyOf(latenciesMicros, n);
        Arrays.sort(copy);
        int idx = (int) Math.ceil(percentile / 100.0 * n) - 1;
        idx = Math.max(0, Math.min(n - 1, idx));
        return copy[idx];
    }

    /** Snapshot for the /stats endpoint and the performance report. */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long submissions = searchSubmissions.get();
        long rows = dbRowsWritten.get();

        Map<String, Object> cache = new LinkedHashMap<>();
        cache.put("hits", hits);
        cache.put("misses", misses);
        cache.put("hitRate", round(cacheHitRate()));
        m.put("cache", cache);

        Map<String, Object> db = new LinkedHashMap<>();
        db.put("reads", dbReads.get());
        db.put("rowsWritten", rows);
        db.put("flushes", flushCount.get());
        m.put("db", db);

        Map<String, Object> writes = new LinkedHashMap<>();
        writes.put("searchSubmissions", submissions);
        writes.put("dbRowsWritten", rows);
        // How many synchronous writes we avoided by buffering+aggregating.
        writes.put("writeReductionRatio", rows == 0 ? null : round((double) submissions / rows));
        writes.put("writesSavedPercent", submissions == 0 ? null
                : round(100.0 * (submissions - rows) / submissions));
        m.put("batchWrites", writes);

        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("samples", Math.min(latencyTotal.get(), LATENCY_RING));
        latency.put("p50Micros", latencyPercentileMicros(50));
        latency.put("p95Micros", latencyPercentileMicros(95));
        latency.put("p99Micros", latencyPercentileMicros(99));
        m.put("suggestLatency", latency);

        return m;
    }

    private static double round(double d) {
        return Math.round(d * 1000.0) / 1000.0;
    }
}
