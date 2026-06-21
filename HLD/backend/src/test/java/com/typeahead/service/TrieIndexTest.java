package com.typeahead.service;

import com.typeahead.config.AppProperties;
import com.typeahead.model.Suggestion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrieIndexTest {

    private TrieIndex newTrie() {
        return new TrieIndex(new AppProperties());
    }

    @Test
    void returnsPrefixMatchesSortedByCountDesc() {
        TrieIndex trie = newTrie();
        trie.build(Map.of(
                "iphone", 100L,
                "iphone 15", 80L,
                "iphone charger", 60L,
                "ipad", 70L,
                "java", 999L
        ));

        List<Suggestion> ip = trie.candidates("ip");
        // All results must start with the prefix...
        assertTrue(ip.stream().allMatch(s -> s.query().startsWith("ip")));
        // ...and be sorted by count descending.
        assertEquals("iphone", ip.get(0).query());
        for (int i = 1; i < ip.size(); i++) {
            assertTrue(ip.get(i - 1).count() >= ip.get(i).count());
        }
        // "java" must not appear under prefix "ip".
        assertTrue(ip.stream().noneMatch(s -> s.query().equals("java")));
    }

    @Test
    void unknownPrefixReturnsEmpty() {
        TrieIndex trie = newTrie();
        trie.build(Map.of("hello", 5L));
        assertTrue(trie.candidates("zzz").isEmpty());
    }

    @Test
    void applyDeltasInsertsNewQueryAndBumpsExisting() {
        TrieIndex trie = newTrie();
        trie.build(Map.of("apple", 10L));

        Map<String, Long> newCounts = trie.applyDeltas(Map.of("apple", 5L, "apricot", 3L));
        assertEquals(15L, newCounts.get("apple"));
        assertEquals(3L, newCounts.get("apricot"));
        assertEquals(15L, trie.getCount("apple"));

        List<Suggestion> ap = trie.candidates("ap");
        assertTrue(ap.stream().anyMatch(s -> s.query().equals("apricot")));
        assertEquals("apple", ap.get(0).query()); // higher count ranks first
    }
}
