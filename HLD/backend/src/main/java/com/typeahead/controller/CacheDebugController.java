package com.typeahead.controller;

import com.typeahead.service.RankingService;
import com.typeahead.service.SuggestionService;
import com.typeahead.service.cache.DistributedCache;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code GET /cache/debug?prefix=<prefix>&ranking=basic|recency}
 *
 * <p>Shows which logical cache node owns the prefix (per the consistent-hash ring) and
 * whether that prefix is currently a cache HIT or MISS. This is the visible evidence of
 * consistent-hashing routing required by the assignment. It does not mutate hit/miss
 * metrics, so you can inspect routing freely.
 */
@RestController
public class CacheDebugController {

    private final DistributedCache cache;

    public CacheDebugController(DistributedCache cache) {
        this.cache = cache;
    }

    @GetMapping("/cache/debug")
    public Map<String, Object> debug(@RequestParam("prefix") String prefix,
                                     @RequestParam(name = "ranking", required = false) String ranking) {
        RankingService.Mode mode = RankingService.parseMode(ranking);
        return cache.debug(SuggestionService.normalize(prefix), mode);
    }
}
