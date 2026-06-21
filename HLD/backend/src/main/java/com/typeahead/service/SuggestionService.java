package com.typeahead.service;

import com.typeahead.config.AppProperties;
import com.typeahead.model.Suggestion;
import com.typeahead.service.cache.DistributedCache;
import com.typeahead.service.metrics.MetricsService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Orchestrates a /suggest request:
 * <pre>
 *   normalise prefix
 *     -> distributed cache (consistent hashing)   [fast path]
 *        -> on miss: compute from the Trie index   [fallback to indexed primary store]
 *           -> (recency mode) blend in live recency + currently-trending matches
 *        -> store result in cache with a mode-specific TTL
 * </pre>
 * Server-side latency of every call is recorded for the p50/p95 report.
 */
@Service
public class SuggestionService {

    private final DistributedCache cache;
    private final TrieIndex trie;
    private final RankingService ranking;
    private final TrendingService trending;
    private final MetricsService metrics;
    private final int maxResults;

    public SuggestionService(DistributedCache cache, TrieIndex trie, RankingService ranking,
                             TrendingService trending, MetricsService metrics, AppProperties props) {
        this.cache = cache;
        this.trie = trie;
        this.ranking = ranking;
        this.trending = trending;
        this.metrics = metrics;
        this.maxResults = props.getSuggest().getMaxResults();
    }

    /** Lower-cases and trims so mixed-case input ("IPhone") matches the indexed data. */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase();
    }

    public List<Suggestion> suggest(String rawPrefix, RankingService.Mode mode) {
        long start = System.nanoTime();
        try {
            String prefix = normalize(rawPrefix);
            if (prefix.isEmpty()) {
                return List.of();   // empty/missing input handled gracefully
            }

            List<Suggestion> cached = cache.get(prefix, mode);
            if (cached != null) {
                return cached;
            }

            List<Suggestion> result = compute(prefix, mode);
            cache.put(prefix, mode, result);
            return result;
        } finally {
            metrics.recordSuggestLatency(System.nanoTime() - start);
        }
    }

    private List<Suggestion> compute(String prefix, RankingService.Mode mode) {
        List<Suggestion> pool = trie.candidates(prefix);

        if (mode == RankingService.Mode.BASIC) {
            // Candidates already arrive sorted by count desc; just trim.
            return pool.size() > maxResults ? new ArrayList<>(pool.subList(0, maxResults)) : pool;
        }

        // RECENCY: widen the pool with currently-trending queries that match the prefix,
        // so a surging query can surface even if it is not a top-by-count candidate.
        Set<String> seen = new LinkedHashSet<>();
        List<Suggestion> candidates = new ArrayList<>();
        for (Suggestion s : pool) {
            if (seen.add(s.query())) candidates.add(s);
        }
        for (String q : trending.trendingMatching(prefix, maxResults * 3)) {
            if (seen.add(q)) candidates.add(Suggestion.ofCount(q, trie.getCount(q)));
        }

        // Re-score every candidate with the blended popularity+recency score and sort.
        List<Suggestion> scored = new ArrayList<>(candidates.size());
        for (Suggestion s : candidates) {
            double score = ranking.score(s.query(), s.count(), mode);
            scored.add(s.withScore(score));
        }
        scored.sort(Comparator.comparingDouble(Suggestion::score).reversed());
        return scored.size() > maxResults ? new ArrayList<>(scored.subList(0, maxResults)) : scored;
    }
}
