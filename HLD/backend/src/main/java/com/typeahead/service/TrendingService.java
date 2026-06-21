package com.typeahead.service;

import com.typeahead.config.AppProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tracks RECENT search activity with a sliding window of fixed-size time buckets,
 * and answers two questions:
 *   1. What is currently trending?  (top queries by recent, recency-weighted activity)
 *   2. How "hot" is a given query right now?  (its recency score, fed into ranking)
 *
 * <h3>Why a bucketed sliding window?</h3>
 * The window directly answers the assignment's required points about recency:
 * <ul>
 *   <li><b>Tracking:</b> every /search increments the CURRENT time bucket for that query.</li>
 *   <li><b>Effect on ranking:</b> recentScore = sum over live buckets of (count * weight),
 *       where newer buckets get a higher weight, so very recent activity matters most.</li>
 *   <li><b>No permanent over-ranking:</b> a bucket older than the window is reused
 *       (cleared) on the next lap, so a query that spiked briefly decays to zero recent
 *       score automatically as time passes -- it cannot stay "trending" forever.</li>
 * </ul>
 *
 * Buckets are arranged as a ring of {@code numBuckets = windowMinutes*60 / bucketSeconds}
 * slots. The absolute slot number is {@code now / bucketMillis}; the physical index is
 * {@code slot % numBuckets}. When we touch a physical slot whose stored slot number is
 * stale, we clear it first -- that is the "old activity falls out of the window" step.
 */
@Service
public class TrendingService {

    private final long bucketMillis;
    private final int numBuckets;
    private final int topK;
    private final Bucket[] buckets;

    /** One time bucket: which absolute slot it currently holds + per-query counters. */
    private static final class Bucket {
        volatile long slot = -1;
        final ConcurrentHashMap<String, LongAdder> counts = new ConcurrentHashMap<>();
    }

    public TrendingService(AppProperties props) {
        AppProperties.Trending t = props.getTrending();
        this.bucketMillis = t.getBucketSeconds() * 1000L;
        this.numBuckets = Math.max(1, (t.getWindowMinutes() * 60) / t.getBucketSeconds());
        this.topK = t.getTopK();
        this.buckets = new Bucket[numBuckets];
        for (int i = 0; i < numBuckets; i++) buckets[i] = new Bucket();
    }

    private long currentSlot() { return System.currentTimeMillis() / bucketMillis; }

    /** Record one search of {@code query} into the current time bucket. */
    public void record(String query) {
        long slot = currentSlot();
        Bucket b = buckets[(int) Math.floorMod(slot, numBuckets)];
        if (b.slot != slot) {
            synchronized (b) {
                if (b.slot != slot) {  // this physical slot wrapped around: drop stale data
                    b.counts.clear();
                    b.slot = slot;
                }
            }
        }
        b.counts.computeIfAbsent(query, k -> new LongAdder()).increment();
    }

    /**
     * Recency-weighted activity for one query over the live window.
     * age 0 = current bucket (highest weight), age numBuckets-1 = oldest live bucket.
     */
    public double recentScore(String query) {
        long cur = currentSlot();
        double score = 0;
        for (Bucket b : buckets) {
            long bs = b.slot;
            long age = cur - bs;
            if (bs < 0 || age < 0 || age >= numBuckets) continue; // empty or expired
            LongAdder a = b.counts.get(query);
            if (a == null) continue;
            double weight = numBuckets - age;   // linear recency weighting
            score += a.sum() * weight;
        }
        return score;
    }

    /** Top {@code topK} queries by recency score across the live window (the trending list). */
    public List<TrendingItem> trending() {
        return trending(topK);
    }

    public List<TrendingItem> trending(int k) {
        Map<String, Double> agg = aggregateLiveWindow();
        List<TrendingItem> items = new ArrayList<>(agg.size());
        agg.forEach((q, s) -> items.add(new TrendingItem(q, s)));
        items.sort(Comparator.comparingDouble(TrendingItem::score).reversed());
        return items.size() > k ? new ArrayList<>(items.subList(0, k)) : items;
    }

    /**
     * Queries currently in the trending set that match {@code prefix}. Used by the
     * suggestion engine in recency mode so a surging query can appear even if it is not
     * among a prefix node's top-by-count candidates.
     */
    public List<String> trendingMatching(String prefix, int limit) {
        List<TrendingItem> all = trending(Math.max(limit, topK) * 4);
        List<String> out = new ArrayList<>();
        for (TrendingItem it : all) {
            if (it.query().startsWith(prefix)) {
                out.add(it.query());
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    private Map<String, Double> aggregateLiveWindow() {
        long cur = currentSlot();
        Map<String, Double> agg = new HashMap<>();
        for (Bucket b : buckets) {
            long bs = b.slot;
            long age = cur - bs;
            if (bs < 0 || age < 0 || age >= numBuckets) continue;
            double weight = numBuckets - age;
            b.counts.forEach((q, adder) -> agg.merge(q, adder.sum() * weight, Double::sum));
        }
        return agg;
    }

    public record TrendingItem(String query, double score) { }
}
