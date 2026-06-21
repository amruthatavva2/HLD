package com.typeahead.controller;

import com.typeahead.service.TrieIndex;
import com.typeahead.service.cache.DistributedCache;
import com.typeahead.service.metrics.MetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /stats} -> everything needed for the performance report in one place:
 * cache hit rate, DB read/write counts, batch write-reduction ratio, /suggest p50/p95
 * latency, dataset size, and the per-node key distribution (consistent-hashing balance).
 */
@RestController
public class StatsController {

    private final MetricsService metrics;
    private final DistributedCache cache;
    private final TrieIndex trie;

    public StatsController(MetricsService metrics, DistributedCache cache, TrieIndex trie) {
        this.metrics = metrics;
        this.cache = cache;
        this.trie = trie;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>(metrics.snapshot());
        out.put("datasetSize", trie.size());

        Map<String, Object> cacheInfo = new LinkedHashMap<>();
        cacheInfo.put("nodes", cache.ring().nodes());
        cacheInfo.put("ringPositions", cache.ring().vnodeCount());
        cacheInfo.put("currentEntriesPerNode", cache.nodeSizes());
        cacheInfo.put("keyDistributionSample10k", cache.distributionSample(10_000));
        out.put("consistentHashing", cacheInfo);

        return out;
    }
}
