package com.typeahead.controller;

import com.typeahead.model.Suggestion;
import com.typeahead.service.RankingService;
import com.typeahead.service.SuggestionService;
import com.typeahead.service.TrendingService;
import com.typeahead.service.batch.BatchWriteBuffer;
import com.typeahead.service.batch.BatchWriter;
import com.typeahead.service.metrics.MetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The two core endpoints:
 * <ul>
 *   <li>{@code GET  /suggest?q=<prefix>&ranking=basic|recency} -> up to N suggestions.</li>
 *   <li>{@code POST /search} {"query": "..."} -> dummy response + records the search.</li>
 * </ul>
 */
@RestController
public class SuggestController {

    private final SuggestionService suggestions;
    private final BatchWriteBuffer buffer;
    private final BatchWriter batchWriter;
    private final TrendingService trending;
    private final MetricsService metrics;

    public SuggestController(SuggestionService suggestions, BatchWriteBuffer buffer,
                             BatchWriter batchWriter, TrendingService trending, MetricsService metrics) {
        this.suggestions = suggestions;
        this.buffer = buffer;
        this.batchWriter = batchWriter;
        this.trending = trending;
        this.metrics = metrics;
    }

    /**
     * Returns prefix-matching suggestions. {@code q} may be empty/missing/mixed-case; all are
     * handled gracefully (empty input -> empty list). {@code ranking} defaults to "recency".
     */
    @GetMapping("/suggest")
    public List<Suggestion> suggest(@RequestParam(name = "q", required = false) String q,
                                    @RequestParam(name = "ranking", required = false) String ranking) {
        RankingService.Mode mode = RankingService.parseMode(ranking);
        return suggestions.suggest(q, mode);
    }

    /**
     * Records a submitted search. The write does NOT hit the database synchronously: the
     * query is aggregated in the batch buffer and the live recency window is bumped, then a
     * fixed dummy response is returned. Counts reach the DB on the next flush.
     */
    @PostMapping("/search")
    public Map<String, String> search(@RequestBody(required = false) SearchRequest body) {
        String query = SuggestionService.normalize(body == null ? null : body.query());
        if (!query.isEmpty()) {
            buffer.add(query);              // aggregated, flushed in batches
            trending.record(query);         // live recency signal (trending updates immediately)
            metrics.searchSubmitted();
            batchWriter.flushIfFull();      // size-based flush trigger
        }
        return Map.of("message", "Searched");
    }

    public record SearchRequest(String query) { }
}
