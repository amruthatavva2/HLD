package com.typeahead.model;

/**
 * One typeahead suggestion returned to the UI.
 *
 * @param query the suggested query text
 * @param count the all-time search count (historical popularity)
 * @param score the ranking score actually used to order this result. In basic mode this
 *              equals {@code count}; in recency mode it is the blended popularity+recency
 *              score. Exposing it makes the difference between the two rankings visible
 *              in the API response / logs (an assignment requirement).
 */
public record Suggestion(String query, long count, double score) {

    /** Convenience factory for the basic ranking, where the score is just the count. */
    public static Suggestion ofCount(String query, long count) {
        return new Suggestion(query, count, count);
    }

    public Suggestion withScore(double newScore) {
        return new Suggestion(query, count, newScore);
    }
}
