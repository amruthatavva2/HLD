package com.typeahead;

import com.typeahead.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Search Typeahead System.
 *
 * <p>High-level flow:
 * <ul>
 *   <li>GET /suggest  -> distributed cache (consistent hashing) -> in-memory Trie index</li>
 *   <li>POST /search  -> batch-write buffer (aggregated) + live recency window</li>
 *   <li>A scheduled batch writer flushes aggregated counts to the H2 primary store</li>
 * </ul>
 *
 * {@code @EnableScheduling} powers the periodic batch flush.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
public class TypeaheadApplication {
    public static void main(String[] args) {
        SpringApplication.run(TypeaheadApplication.class, args);
    }
}
