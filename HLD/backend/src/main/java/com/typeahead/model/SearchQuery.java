package com.typeahead.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * The durable record of a query and how many times it has been searched.
 * This is the row stored in the H2 "primary data store". The query string itself
 * is the natural primary key, and we index it so prefix scans stay cheap.
 */
@Entity
@Table(name = "search_query", indexes = @Index(name = "idx_query", columnList = "query"))
public class SearchQuery {

    @Id
    @Column(length = 512)
    private String query;

    @Column(nullable = false)
    private long count;

    /** Epoch millis of the last time this query was searched (used for diagnostics). */
    @Column(nullable = false)
    private long lastSearchedAt;

    protected SearchQuery() { }

    public SearchQuery(String query, long count, long lastSearchedAt) {
        this.query = query;
        this.count = count;
        this.lastSearchedAt = lastSearchedAt;
    }

    public String getQuery() { return query; }
    public long getCount() { return count; }
    public long getLastSearchedAt() { return lastSearchedAt; }

    public void setCount(long count) { this.count = count; }
    public void setLastSearchedAt(long lastSearchedAt) { this.lastSearchedAt = lastSearchedAt; }
}
