package com.typeahead.controller;

import com.typeahead.service.TrendingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code GET /trending?limit=N} -> the queries with the most recent, recency-weighted
 * activity in the sliding window. Powers the UI's "Trending searches" section.
 */
@RestController
public class TrendingController {

    private final TrendingService trending;

    public TrendingController(TrendingService trending) {
        this.trending = trending;
    }

    @GetMapping("/trending")
    public List<TrendingService.TrendingItem> trending(
            @RequestParam(name = "limit", required = false, defaultValue = "10") int limit) {
        return trending.trending(limit);
    }
}
