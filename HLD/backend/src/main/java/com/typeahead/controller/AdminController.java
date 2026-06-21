package com.typeahead.controller;

import com.typeahead.service.batch.BatchWriter;
import com.typeahead.service.cache.ConsistentHashRing;
import com.typeahead.service.cache.DistributedCache;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Demo / operations endpoints (not part of the core API, but invaluable for the viva):
 * <ul>
 *   <li>{@code POST /admin/flush} -> force a batch flush now.</li>
 *   <li>{@code POST /admin/cache/clear} -> empty all cache nodes.</li>
 *   <li>{@code POST /admin/node?action=add|remove&id=...} -> change the ring and report how
 *       FEW keys had to move -- the headline benefit of consistent hashing.</li>
 * </ul>
 */
@RestController
public class AdminController {

    private final BatchWriter batchWriter;
    private final DistributedCache cache;

    public AdminController(BatchWriter batchWriter, DistributedCache cache) {
        this.batchWriter = batchWriter;
        this.cache = cache;
    }

    @PostMapping("/admin/flush")
    public Map<String, Object> flush() {
        int rows = batchWriter.flushNow();
        return Map.of("flushedRows", rows);
    }

    @PostMapping("/admin/cache/clear")
    public Map<String, Object> clearCache() {
        cache.clearAll();
        return Map.of("status", "cache cleared");
    }

    /**
     * Add or remove a cache node and measure the remap: of a fixed sample of keys, how many
     * change owner. With consistent hashing this is ~1/N; with hash%N it would be ~all.
     */
    @PostMapping("/admin/node")
    public Map<String, Object> node(@RequestParam("action") String action,
                                    @RequestParam("id") String id) {
        ConsistentHashRing ring = cache.ring();
        int sample = 10_000;
        Map<Integer, String> before = ownership(ring, sample);

        if (action.equalsIgnoreCase("add")) {
            cache.addNode(id);
        } else if (action.equalsIgnoreCase("remove")) {
            cache.removeNode(id);
        } else {
            return Map.of("error", "action must be 'add' or 'remove'");
        }

        Map<Integer, String> after = ownership(ring, sample);
        int moved = 0;
        for (int i = 0; i < sample; i++) {
            String b = before.get(i), a = after.get(i);
            if (b == null ? a != null : !b.equals(a)) moved++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", action);
        out.put("nodeId", id);
        out.put("nodesNow", ring.nodes());
        out.put("sampleKeys", sample);
        out.put("keysMoved", moved);
        out.put("keysMovedPercent", Math.round(10000.0 * moved / sample) / 100.0);
        out.put("note", "Consistent hashing moves ~1/N of keys; hash%N would move almost all.");
        return out;
    }

    private Map<Integer, String> ownership(ConsistentHashRing ring, int sample) {
        Map<Integer, String> m = new HashMap<>(sample * 2);
        for (int i = 0; i < sample; i++) {
            m.put(i, ring.getNode("sample-prefix-" + i));
        }
        return m;
    }
}
