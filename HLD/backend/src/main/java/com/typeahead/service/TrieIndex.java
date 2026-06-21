package com.typeahead.service;

import com.typeahead.config.AppProperties;
import com.typeahead.model.Suggestion;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * In-memory prefix index ("trie") that serves typeahead candidates in O(prefix length).
 *
 * <h3>Why a trie?</h3>
 * It is the classic autocomplete structure. Walking the tree by characters reaches the
 * node for a prefix in time proportional to the prefix length, independent of dataset
 * size. To avoid scanning a possibly-huge subtree on every keystroke, <b>each node caches
 * its top-N descendant queries by all-time count</b>. A lookup therefore just walks to the
 * prefix node and returns that precomputed list -- no subtree traversal at request time.
 *
 * <p>This trie is the indexed, in-sync view of the durable H2 primary store: it is built
 * from H2 at startup and updated when the batch writer flushes. The authoritative counts
 * live in {@link #counts} so flushes can write absolute values (no read-modify-write in SQL).
 *
 * <p>Concurrency: a single {@link ReentrantReadWriteLock} guards the tree. Reads (suggest)
 * are concurrent; the batch flush takes the write lock briefly. Lookups copy the small
 * candidate list (<= N) so scoring happens outside the lock.
 */
@Service
public class TrieIndex {

    private final int candidatesPerNode;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** Authoritative current count per query (mirror of the durable store, kept hot). */
    private final ConcurrentHashMap<String, Long> counts = new ConcurrentHashMap<>();

    private volatile Node root = new Node();

    /**
     * A trie node. {@code candidates} holds up to {@code candidatesPerNode} of the best
     * (highest count) queries found anywhere in this node's subtree, kept sorted desc.
     */
    private static final class Node {
        final Map<Character, Node> children = new HashMap<>();
        final ArrayList<Suggestion> candidates = new ArrayList<>();
    }

    public TrieIndex(AppProperties props) {
        this.candidatesPerNode = props.getSuggest().getCandidatesPerNode();
    }

    /** Bulk-build the trie from a query->count map (startup). Replaces any existing data. */
    public void build(Map<String, Long> seed) {
        lock.writeLock().lock();
        try {
            counts.clear();
            counts.putAll(seed);
            Node newRoot = new Node();
            seed.forEach((q, c) -> insert(newRoot, q, c));
            this.root = newRoot;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Apply aggregated deltas from a batch flush. Returns the new absolute counts so the
     * caller can persist them. New queries are inserted; existing ones are bumped.
     */
    public Map<String, Long> applyDeltas(Map<String, Long> deltas) {
        Map<String, Long> newCounts = new HashMap<>(deltas.size() * 2);
        lock.writeLock().lock();
        try {
            deltas.forEach((q, delta) -> {
                long updated = counts.merge(q, delta, Long::sum);
                newCounts.put(q, updated);
                insert(root, q, updated);  // refresh this query's count along its path
            });
        } finally {
            lock.writeLock().unlock();
        }
        return newCounts;
    }

    /** Insert/refresh {@code query} with {@code count}, updating top-N candidates on the path. */
    private void insert(Node node, String query, long count) {
        Suggestion s = Suggestion.ofCount(query, count);
        Node cur = node;
        addCandidate(cur, s);                 // root represents the empty prefix
        for (int i = 0; i < query.length(); i++) {
            cur = cur.children.computeIfAbsent(query.charAt(i), k -> new Node());
            addCandidate(cur, s);
        }
    }

    /** Maintain a node's sorted top-N candidate list. O(N), with cheap pruning. */
    private void addCandidate(Node node, Suggestion s) {
        ArrayList<Suggestion> list = node.candidates;
        // Update in place if this query is already a candidate here.
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).query().equals(s.query())) {
                list.set(i, s);
                resort(list);
                return;
            }
        }
        if (list.size() < candidatesPerNode) {
            list.add(s);
            resort(list);
        } else if (s.count() > list.get(list.size() - 1).count()) {
            list.set(list.size() - 1, s);   // replace the weakest
            resort(list);
        }
        // else: list is full and this query is weaker than all of them -> prune (skip).
    }

    private static void resort(ArrayList<Suggestion> list) {
        list.sort(Comparator.comparingLong(Suggestion::count).reversed());
    }

    /**
     * Candidate pool for {@code prefix}: up to N queries in the prefix's subtree, sorted by
     * count desc. Returns a copy so callers can re-rank freely. Empty if no query matches.
     */
    public List<Suggestion> candidates(String prefix) {
        lock.readLock().lock();
        try {
            Node cur = root;
            for (int i = 0; i < prefix.length(); i++) {
                cur = cur.children.get(prefix.charAt(i));
                if (cur == null) return List.of();
            }
            return new ArrayList<>(cur.candidates);
        } finally {
            lock.readLock().unlock();
        }
    }

    public long getCount(String query) {
        return counts.getOrDefault(query, 0L);
    }

    public int size() {
        return counts.size();
    }
}
