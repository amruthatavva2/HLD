package com.typeahead.service.cache;

import com.typeahead.model.Suggestion;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One logical cache node. In a real deployment this would be a separate Redis/Memcached
 * server; here each node is an in-process map, which is enough to demonstrate distribution
 * and consistent hashing while keeping the project trivial to run locally.
 *
 * <p>Entries carry an absolute expiry timestamp, giving per-entry TTL. Expired entries are
 * treated as misses and evicted lazily on access.
 */
public class CacheNode {

    private final String id;
    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    private record Entry(List<Suggestion> suggestions, long expiresAt) {
        boolean isExpired(long now) { return now >= expiresAt; }
    }

    public CacheNode(String id) { this.id = id; }

    public String id() { return id; }

    /** Returns the cached suggestions, or null on miss/expiry. */
    public List<Suggestion> get(String key) {
        Entry e = store.get(key);
        if (e == null) return null;
        if (e.isExpired(System.currentTimeMillis())) {
            store.remove(key, e);
            return null;
        }
        return e.suggestions();
    }

    /** True if the key is present and unexpired (used by /cache/debug without side effects). */
    public boolean has(String key) {
        Entry e = store.get(key);
        return e != null && !e.isExpired(System.currentTimeMillis());
    }

    public void put(String key, List<Suggestion> suggestions, long ttlMs) {
        store.put(key, new Entry(suggestions, System.currentTimeMillis() + ttlMs));
    }

    public void remove(String key) { store.remove(key); }

    public void clear() { store.clear(); }

    public int size() { return store.size(); }
}
