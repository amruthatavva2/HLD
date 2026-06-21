package com.typeahead.service.cache;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsistentHashRingTest {

    @Test
    void distributesKeysRoughlyEvenlyAcrossNodes() {
        ConsistentHashRing ring = new ConsistentHashRing(150);
        ring.addNode("a");
        ring.addNode("b");
        ring.addNode("c");

        Map<String, Integer> counts = new HashMap<>();
        int keys = 30_000;
        for (int i = 0; i < keys; i++) {
            counts.merge(ring.getNode("prefix-" + i), 1, Integer::sum);
        }
        // With 150 vnodes/node, each of the 3 nodes should hold roughly a third (~33%).
        for (int c : counts.values()) {
            double share = (double) c / keys;
            assertTrue(share > 0.25 && share < 0.42, "share out of range: " + share);
        }
    }

    @Test
    void addingNodeRemapsOnlyAboutOneOverNKeys() {
        ConsistentHashRing ring = new ConsistentHashRing(150);
        ring.addNode("a");
        ring.addNode("b");
        ring.addNode("c");

        int keys = 30_000;
        String[] before = new String[keys];
        for (int i = 0; i < keys; i++) before[i] = ring.getNode("prefix-" + i);

        ring.addNode("d"); // 3 -> 4 nodes

        int moved = 0;
        for (int i = 0; i < keys; i++) {
            if (!before[i].equals(ring.getNode("prefix-" + i))) moved++;
        }
        double movedFraction = (double) moved / keys;
        // Going 3->4 nodes, consistent hashing should move ~1/4 of keys. Allow generous slack,
        // but it must be far below the ~100% that hash%N would move.
        assertTrue(movedFraction > 0.10 && movedFraction < 0.40,
                "moved fraction should be ~1/4, was " + movedFraction);
    }

    @Test
    void emptyRingReturnsNullAndWrapsAround() {
        ConsistentHashRing ring = new ConsistentHashRing(10);
        assertEquals(null, ring.getNode("x"));
        ring.addNode("only");
        assertNotNull(ring.getNode("anything")); // single node owns everything
        assertEquals("only", ring.getNode("anything"));
    }
}
