package com.typeahead.service;

import com.typeahead.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankingServiceTest {

    @Test
    void basicModeScoreEqualsCount() {
        AppProperties props = new AppProperties();
        RankingService ranking = new RankingService(props, new TrendingService(props));
        assertEquals(500.0, ranking.score("anything", 500, RankingService.Mode.BASIC));
    }

    @Test
    void recencyModeBoostsRecentlySearchedQueryAboveAPopularButStaleOne() {
        AppProperties props = new AppProperties();
        props.getRanking().setRecencyWeight(0.7);
        TrendingService trending = new TrendingService(props);
        RankingService ranking = new RankingService(props, trending);

        // "popular" has a huge all-time count but no recent activity.
        long popularCount = 100_000;
        // "surging" is rare all-time but searched a lot right now.
        long surgingCount = 50;
        for (int i = 0; i < 40; i++) trending.record("surging");

        double popular = ranking.score("popular", popularCount, RankingService.Mode.RECENCY);
        double surging = ranking.score("surging", surgingCount, RankingService.Mode.RECENCY);

        assertTrue(surging > popular,
                "a strongly surging query should out-rank a stale popular one in recency mode "
                        + "(surging=" + surging + ", popular=" + popular + ")");

        // In basic mode the popular query still wins (no recency applied).
        assertTrue(ranking.score("popular", popularCount, RankingService.Mode.BASIC)
                > ranking.score("surging", surgingCount, RankingService.Mode.BASIC));
    }
}
