package com.typeahead.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Strongly-typed view of the {@code typeahead.*} settings in application.yml.
 * Centralising tuning knobs here keeps the rest of the code free of magic numbers
 * and makes every trade-off (TTLs, batch size, recency weight) explicit and explainable.
 */
@ConfigurationProperties(prefix = "typeahead")
public class AppProperties {

    @NestedConfigurationProperty
    private Suggest suggest = new Suggest();
    @NestedConfigurationProperty
    private Cache cache = new Cache();
    @NestedConfigurationProperty
    private Batch batch = new Batch();
    @NestedConfigurationProperty
    private Trending trending = new Trending();
    @NestedConfigurationProperty
    private Ranking ranking = new Ranking();
    @NestedConfigurationProperty
    private Dataset dataset = new Dataset();

    public Suggest getSuggest() { return suggest; }
    public Cache getCache() { return cache; }
    public Batch getBatch() { return batch; }
    public Trending getTrending() { return trending; }
    public Ranking getRanking() { return ranking; }
    public Dataset getDataset() { return dataset; }

    public static class Suggest {
        private int maxResults = 10;
        private int candidatesPerNode = 50;
        public int getMaxResults() { return maxResults; }
        public void setMaxResults(int v) { this.maxResults = v; }
        public int getCandidatesPerNode() { return candidatesPerNode; }
        public void setCandidatesPerNode(int v) { this.candidatesPerNode = v; }
    }

    public static class Cache {
        private int nodes = 3;
        private int vnodesPerNode = 150;
        private long ttlBasicMs = 60000;
        private long ttlRecencyMs = 5000;
        public int getNodes() { return nodes; }
        public void setNodes(int v) { this.nodes = v; }
        public int getVnodesPerNode() { return vnodesPerNode; }
        public void setVnodesPerNode(int v) { this.vnodesPerNode = v; }
        public long getTtlBasicMs() { return ttlBasicMs; }
        public void setTtlBasicMs(long v) { this.ttlBasicMs = v; }
        public long getTtlRecencyMs() { return ttlRecencyMs; }
        public void setTtlRecencyMs(long v) { this.ttlRecencyMs = v; }
    }

    public static class Batch {
        private int size = 500;
        private long flushIntervalMs = 2000;
        public int getSize() { return size; }
        public void setSize(int v) { this.size = v; }
        public long getFlushIntervalMs() { return flushIntervalMs; }
        public void setFlushIntervalMs(long v) { this.flushIntervalMs = v; }
    }

    public static class Trending {
        private int windowMinutes = 10;
        private int bucketSeconds = 60;
        private int topK = 10;
        public int getWindowMinutes() { return windowMinutes; }
        public void setWindowMinutes(int v) { this.windowMinutes = v; }
        public int getBucketSeconds() { return bucketSeconds; }
        public void setBucketSeconds(int v) { this.bucketSeconds = v; }
        public int getTopK() { return topK; }
        public void setTopK(int v) { this.topK = v; }
    }

    public static class Ranking {
        private double recencyWeight = 0.7;
        public double getRecencyWeight() { return recencyWeight; }
        public void setRecencyWeight(double v) { this.recencyWeight = v; }
    }

    public static class Dataset {
        private String path = "./data/queries.csv";
        private int size = 120000;
        private long seed = 42;
        public String getPath() { return path; }
        public void setPath(String v) { this.path = v; }
        public int getSize() { return size; }
        public void setSize(int v) { this.size = v; }
        public long getSeed() { return seed; }
        public void setSeed(long v) { this.seed = v; }
    }
}
