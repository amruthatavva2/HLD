package com.typeahead.ingest;

import com.typeahead.config.AppProperties;
import com.typeahead.service.TrieIndex;
import com.typeahead.service.metrics.MetricsService;
import com.typeahead.tools.DatasetGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Startup ingestion. On boot:
 * <ol>
 *   <li>Generate the dataset CSV if it does not exist (reproducible, seeded).</li>
 *   <li>If the H2 table is empty, bulk-load the CSV into it with batched inserts;
 *       otherwise reuse the counts already in H2 (so restarts preserve accumulated data).</li>
 *   <li>Build the in-memory {@link TrieIndex} from those counts.</li>
 * </ol>
 */
@Component
public class DatasetLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatasetLoader.class);

    private final AppProperties props;
    private final JdbcTemplate jdbc;
    private final TrieIndex trie;
    private final MetricsService metrics;

    public DatasetLoader(AppProperties props, JdbcTemplate jdbc, TrieIndex trie, MetricsService metrics) {
        this.props = props;
        this.jdbc = jdbc;
        this.trie = trie;
        this.metrics = metrics;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        long t0 = System.currentTimeMillis();
        AppProperties.Dataset ds = props.getDataset();
        Path csv = Paths.get(ds.getPath());

        DatasetGenerator.generateIfMissing(csv, ds.getSize(), ds.getSeed());

        Long existing = jdbc.queryForObject("SELECT COUNT(*) FROM search_query", Long.class);
        Map<String, Long> counts;
        if (existing != null && existing > 0) {
            counts = loadCountsFromDb();
            log.info("Loaded {} existing queries from H2 (skipping CSV import)", counts.size());
        } else {
            counts = readCsv(csv);
            bulkInsert(counts);
            log.info("Imported {} queries from {} into H2", counts.size(), csv.toAbsolutePath());
        }

        metrics.addDbReads(counts.size());
        trie.build(counts);
        log.info("Trie built with {} queries in {} ms", trie.size(), System.currentTimeMillis() - t0);
    }

    private Map<String, Long> readCsv(Path csv) throws Exception {
        Map<String, Long> counts = new HashMap<>(200_000);
        try (BufferedReader r = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String line = r.readLine(); // header
            while ((line = r.readLine()) != null) {
                int comma = line.lastIndexOf(',');
                if (comma <= 0) continue;
                String query = line.substring(0, comma).trim().toLowerCase();
                try {
                    counts.put(query, Long.parseLong(line.substring(comma + 1).trim()));
                } catch (NumberFormatException ignore) { /* skip malformed row */ }
            }
        }
        return counts;
    }

    private Map<String, Long> loadCountsFromDb() {
        Map<String, Long> counts = new HashMap<>(200_000);
        jdbc.query("SELECT query, count FROM search_query",
                rs -> { counts.put(rs.getString(1), rs.getLong(2)); });
        return counts;
    }

    private void bulkInsert(Map<String, Long> counts) {
        long now = System.currentTimeMillis();
        List<Object[]> batch = new ArrayList<>(5000);
        final String sql = "INSERT INTO search_query (query, count, last_searched_at) VALUES (?, ?, ?)";
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            batch.add(new Object[]{ e.getKey(), e.getValue(), now });
            if (batch.size() >= 5000) {
                jdbc.batchUpdate(sql, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) jdbc.batchUpdate(sql, batch);
    }
}
