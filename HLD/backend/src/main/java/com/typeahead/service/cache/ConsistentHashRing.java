package com.typeahead.service.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A consistent-hashing ring that maps a key (here, a cache prefix) to one of several
 * logical cache nodes.
 *
 * <h3>Why consistent hashing?</h3>
 * With plain {@code hash(key) % N}, changing N (adding/removing a cache node) reshuffles
 * almost ALL keys, which would blow away the whole cache. Consistent hashing places nodes
 * and keys on the same circular hash space; a key is owned by the first node found walking
 * clockwise. Adding or removing a node only moves the keys in one arc -- on average ~1/N of
 * keys -- leaving the rest cached. We use <b>virtual nodes</b> (many ring positions per
 * physical node) so the load is spread evenly instead of depending on where a single point
 * happens to land.
 *
 * <p>Thread-safety: the ring is rebuilt copy-on-write on add/remove (rare, admin-triggered)
 * and read lock-free via a {@code volatile} snapshot, so the hot {@link #getNode} path never
 * blocks.
 */
public class ConsistentHashRing {

    private final int vnodesPerNode;
    private volatile Snapshot snapshot;

    private record Snapshot(TreeMap<Long, String> ring, List<String> nodes) { }

    public ConsistentHashRing(int vnodesPerNode) {
        this.vnodesPerNode = vnodesPerNode;
        this.snapshot = new Snapshot(new TreeMap<>(), new ArrayList<>());
    }

    public synchronized void addNode(String nodeId) {
        List<String> nodes = new ArrayList<>(snapshot.nodes());
        if (nodes.contains(nodeId)) return;
        nodes.add(nodeId);
        rebuild(nodes);
    }

    public synchronized void removeNode(String nodeId) {
        List<String> nodes = new ArrayList<>(snapshot.nodes());
        if (!nodes.remove(nodeId)) return;
        rebuild(nodes);
    }

    private void rebuild(List<String> nodes) {
        TreeMap<Long, String> ring = new TreeMap<>();
        for (String node : nodes) {
            for (int i = 0; i < vnodesPerNode; i++) {
                ring.put(hash(node + "#" + i), node);
            }
        }
        this.snapshot = new Snapshot(ring, nodes);
    }

    /** The logical node that owns {@code key}: first vnode clockwise from hash(key). */
    public String getNode(String key) {
        TreeMap<Long, String> ring = snapshot.ring();
        if (ring.isEmpty()) return null;
        long h = hash(key);
        SortedMap<Long, String> tail = ring.tailMap(h);
        Long target = tail.isEmpty() ? ring.firstKey() : tail.firstKey();
        return ring.get(target);
    }

    public List<String> nodes() {
        return new ArrayList<>(snapshot.nodes());
    }

    public int vnodeCount() {
        return snapshot.ring().size();
    }

    /** 64-bit hash from the first 8 bytes of MD5. Deterministic and well-distributed. */
    static long hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(key.getBytes(StandardCharsets.UTF_8));
            long h = 0;
            for (int i = 0; i < 8; i++) {
                h = (h << 8) | (d[i] & 0xff);
            }
            return h & Long.MAX_VALUE; // keep it non-negative
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }
}
