package com.typeahead.service.cache;

import com.typeahead.config.AppProperties;
import com.typeahead.model.Suggestion;
import com.typeahead.service.RankingService;
import com.typeahead.service.metrics.MetricsService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The distributed suggestion cache: N logical {@link CacheNode}s fronted by a
 * {@link ConsistentHashRing}. A prefix is routed to exactly one node by consistent hashing,
 * so this behaves like a sharded cache cluster.
 *
 * <p>Cache key design: the RING is keyed by the normalised prefix only, so "consistent
 * hashing decides which node owns a prefix key" (assignment wording). Within a node, the
 * map key also includes the ranking mode, so basic and recency results for the same prefix
 * live on the same node but in separate slots.
 */
@Service
public class DistributedCache {

    private final AppProperties props;
    private final MetricsService metrics;
    private final ConsistentHashRing ring;
    private final Map<String, CacheNode> nodes = new ConcurrentHashMap<>();

    public DistributedCache(AppProperties props, MetricsService metrics) {
        this.props = props;
        this.metrics = metrics;
        this.ring = new ConsistentHashRing(props.getCache().getVnodesPerNode());
    }

    @PostConstruct
    void init() {
        for (int i = 0; i < props.getCache().getNodes(); i++) {
            addNode("cache-node-" + i);
        }
    }

    private static String slot(RankingService.Mode mode, String prefix) {
        return mode.name() + "|" + prefix;
    }

    private long ttlFor(RankingService.Mode mode) {
        return mode == RankingService.Mode.RECENCY
                ? props.getCache().getTtlRecencyMs()
                : props.getCache().getTtlBasicMs();
    }

    /** Look up suggestions; records a hit/miss in metrics. Returns null on miss. */
    public List<Suggestion> get(String prefix, RankingService.Mode mode) {
        CacheNode node = nodeFor(prefix);
        if (node == null) { metrics.cacheMiss(); return null; }
        List<Suggestion> hit = node.get(slot(mode, prefix));
        if (hit != null) { metrics.cacheHit(); return hit; }
        metrics.cacheMiss();
        return null;
    }

    public void put(String prefix, RankingService.Mode mode, List<Suggestion> suggestions) {
        CacheNode node = nodeFor(prefix);
        if (node != null) node.put(slot(mode, prefix), suggestions, ttlFor(mode));
    }

    /** Drop both rankings for a prefix (called when a flush changes the underlying data). */
    public void invalidate(String prefix) {
        CacheNode node = nodeFor(prefix);
        if (node == null) return;
        node.remove(slot(RankingService.Mode.BASIC, prefix));
        node.remove(slot(RankingService.Mode.RECENCY, prefix));
    }

    public CacheNode nodeFor(String prefix) {
        String id = ring.getNode(prefix);
        return id == null ? null : nodes.get(id);
    }

    /** Routing + hit/miss info for /cache/debug, with NO metrics side effects. */
    public Map<String, Object> debug(String prefix, RankingService.Mode mode) {
        CacheNode node = nodeFor(prefix);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("prefix", prefix);
        m.put("ranking", mode.name().toLowerCase());
        m.put("ownerNode", node == null ? null : node.id());
        m.put("status", node != null && node.has(slot(mode, prefix)) ? "HIT" : "MISS");
        m.put("ringPositions", ring.vnodeCount());
        m.put("allNodes", ring.nodes());
        return m;
    }

    public void clearAll() { nodes.values().forEach(CacheNode::clear); }

    public void addNode(String id) {
        nodes.computeIfAbsent(id, CacheNode::new);
        ring.addNode(id);
    }

    public void removeNode(String id) {
        ring.removeNode(id);
        nodes.remove(id);
    }

    public ConsistentHashRing ring() { return ring; }

    /** Per-node entry counts -> shows how evenly consistent hashing spread the keys. */
    public Map<String, Integer> nodeSizes() {
        Map<String, Integer> m = new LinkedHashMap<>();
        nodes.forEach((id, n) -> m.put(id, n.size()));
        return m;
    }

    /**
     * Assign {@code sampleKeys} synthetic prefixes to nodes to illustrate ring balance,
     * independent of what is currently cached. Used by /stats.
     */
    public Map<String, Integer> distributionSample(int sampleKeys) {
        Map<String, Integer> dist = new LinkedHashMap<>();
        ring.nodes().forEach(n -> dist.put(n, 0));
        for (int i = 0; i < sampleKeys; i++) {
            String owner = ring.getNode("sample-prefix-" + i);
            if (owner != null) dist.merge(owner, 1, Integer::sum);
        }
        return dist;
    }
}
