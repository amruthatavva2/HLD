package com.typeahead.service.batch;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-memory staging area for search-count updates. Every /search lands here instead of
 * writing to the database synchronously.
 *
 * <h3>Aggregation</h3>
 * Counts are kept in a {@code ConcurrentHashMap<query, LongAdder>}, so 10 searches for
 * "iphone" become a single {@code +10} entry rather than 10 separate writes. When the
 * batch writer flushes, it {@link #drain()}s the whole map in one shot and the database
 * sees one row per DISTINCT query -- this is where the write reduction comes from.
 *
 * <p>The buffer is swapped atomically on drain (an {@link AtomicReference} to the live map),
 * so writers and the flusher never block each other.
 */
@Component
public class BatchWriteBuffer {

    private final AtomicReference<ConcurrentHashMap<String, LongAdder>> ref =
            new AtomicReference<>(new ConcurrentHashMap<>());

    /** Stage one search; aggregates repeated queries. */
    public void add(String query) {
        ref.get().computeIfAbsent(query, k -> new LongAdder()).increment();
    }

    /** Number of DISTINCT queries currently buffered (used to trigger size-based flush). */
    public int distinctSize() {
        return ref.get().size();
    }

    /**
     * Atomically take everything currently buffered and reset the buffer to empty.
     * Returns aggregated query -> delta. (A search arriving in the tiny window during the
     * swap may land in the next batch; this is the documented at-least-eventually trade-off.)
     */
    public Map<String, Long> drain() {
        ConcurrentHashMap<String, LongAdder> old = ref.getAndSet(new ConcurrentHashMap<>());
        Map<String, Long> out = new HashMap<>(old.size() * 2);
        old.forEach((q, adder) -> out.put(q, adder.sum()));
        return out;
    }
}
