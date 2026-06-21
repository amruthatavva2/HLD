package com.typeahead.repository;

import com.typeahead.model.SearchQuery;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * JPA access to the durable query-count table. Used for the bulk startup load and
 * the row count check. The hot write path does NOT go through here -- it uses a
 * batched JDBC MERGE inside the batch writer (see BatchWriter) to minimise writes.
 */
public interface SearchQueryRepository extends JpaRepository<SearchQuery, String> {
}
