package com.typeahead.service;

import com.typeahead.config.AppProperties;
import org.springframework.stereotype.Service;

/**
 * Computes the ranking score for a suggestion under the two supported modes.
 *
 * <ul>
 *   <li><b>BASIC</b> (60% requirement): score = all-time count. Historically popular
 *       queries rank first.</li>
 *   <li><b>RECENCY</b> (20% requirement): score = log10(count+1) + beta * recentScore.
 *       <ul>
 *         <li>The historical part is <b>log-dampened</b> so a query with a massive all-time
 *             count cannot drown out everything; a difference of 10x in count is only +1.</li>
 *         <li>The recency part is the recency-weighted activity from {@link TrendingService}.
 *             A query that is being searched right now gets a strong, immediate boost.</li>
 *         <li>Because recentScore decays out of the sliding window, the boost is temporary --
 *             this is how we avoid permanently over-ranking a briefly-popular query.</li>
 *       </ul>
 *       {@code beta} (typeahead.ranking.recency-weight) tunes how aggressively recency
 *       overrides historical popularity.</li>
 * </ul>
 */
@Service
public class RankingService {

    public enum Mode { BASIC, RECENCY }

    private final double recencyWeight;
    private final TrendingService trending;

    public RankingService(AppProperties props, TrendingService trending) {
        this.recencyWeight = props.getRanking().getRecencyWeight();
        this.trending = trending;
    }

    public static Mode parseMode(String raw) {
        if (raw == null) return Mode.RECENCY;            // enhanced ranking is the default
        return raw.equalsIgnoreCase("basic") ? Mode.BASIC : Mode.RECENCY;
    }

    public double score(String query, long count, Mode mode) {
        if (mode == Mode.BASIC) {
            return count;
        }
        double historical = Math.log10(count + 1);
        double recent = trending.recentScore(query);
        return historical + recencyWeight * recent;
    }
}
