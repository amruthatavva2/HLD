package com.typeahead.tools;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Generates a reproducible synthetic search-query dataset ({@code query,count}).
 *
 * <h3>Why synthetic + why Zipf?</h3>
 * Real query logs are private and awkward to ship; a seeded generator gives a fully
 * reproducible 100k+ dataset that runs offline. Counts follow a <b>Zipf-like</b>
 * distribution (count ~ BASE / rank^s) because real search popularity is extremely skewed:
 * a few queries are searched enormously more than the long tail. That skew is what makes
 * "sorted by count" and the trending/recency demos meaningful.
 *
 * <p>A curated set of realistic head queries (e.g. the iphone family) is given explicit
 * high counts so prefix demos like typing "ip" return sensible, recognisable results.
 *
 * <p>Run standalone to (re)generate:
 * {@code mvn -q compile exec:java -Dexec.mainClass=com.typeahead.tools.DatasetGenerator}
 */
public final class DatasetGenerator {

    private DatasetGenerator() { }

    /** Realistic, recognisable head queries with explicit high counts (popularity head). */
    private static final String[][] CURATED = {
            {"iphone", "100000"}, {"iphone 15", "85000"}, {"iphone 15 pro", "78000"},
            {"iphone charger", "60000"}, {"iphone case", "52000"}, {"iphone 14", "47000"},
            {"iphone 15 pro max", "44000"}, {"iphone wallpaper", "31000"}, {"ipad", "70000"},
            {"ipad pro", "41000"}, {"ipad air", "33000"},
            {"samsung galaxy", "66000"}, {"samsung tv", "39000"}, {"samsung watch", "28000"},
            {"java tutorial", "40000"}, {"java jobs", "26000"}, {"java interview questions", "30000"},
            {"javascript tutorial", "38000"}, {"javascript array methods", "21000"},
            {"python tutorial", "57000"}, {"python list comprehension", "24000"}, {"python jobs", "22000"},
            {"amazon prime", "61000"}, {"amazon deals", "43000"}, {"amazon gift card", "29000"},
            {"best laptop", "55000"}, {"best laptop 2024", "37000"}, {"best headphones", "34000"},
            {"best phone", "36000"}, {"best smartwatch", "23000"},
            {"how to cook rice", "27000"}, {"how to tie a tie", "20000"}, {"how to screenshot", "25000"},
            {"weather today", "72000"}, {"weather tomorrow", "35000"},
            {"news today", "59000"}, {"breaking news", "32000"},
            {"nike shoes", "48000"}, {"nike air force 1", "30000"}, {"adidas shoes", "33000"},
            {"netflix", "75000"}, {"netflix login", "28000"}, {"youtube", "92000"},
            {"google maps", "64000"}, {"gmail login", "45000"}, {"facebook login", "41000"},
            {"cheap flights", "38000"}, {"hotels near me", "31000"}, {"restaurants near me", "34000"},
            {"covid symptoms", "26000"}, {"stock market today", "29000"}, {"bitcoin price", "44000"},
            {"laptop deals", "27000"}, {"wireless earbuds", "30000"}, {"gaming laptop", "33000"},
            {"coffee maker", "22000"}, {"air fryer", "39000"}, {"robot vacuum", "21000"},
    };

    /** Word pool used to synthesise the long tail of queries. */
    private static final String[] VOCAB = {
            "iphone", "ipad", "samsung", "galaxy", "pixel", "oneplus", "xiaomi", "laptop", "macbook",
            "dell", "hp", "lenovo", "asus", "monitor", "keyboard", "mouse", "headphones", "earbuds",
            "speaker", "charger", "cable", "case", "screen", "battery", "camera", "drone", "watch",
            "smartwatch", "fitness", "tracker", "tv", "console", "playstation", "xbox", "nintendo",
            "switch", "controller", "router", "modem", "ssd", "hard", "drive", "graphics", "card",
            "processor", "memory", "best", "cheap", "top", "review", "deals", "price", "buy", "online",
            "near", "me", "store", "shop", "discount", "coupon", "sale", "offer", "new", "used",
            "refurbished", "pro", "max", "plus", "ultra", "mini", "lite", "air", "2023", "2024", "2025",
            "how", "to", "what", "is", "why", "when", "where", "guide", "tutorial", "course", "learn",
            "tips", "tricks", "examples", "for", "beginners", "advanced", "java", "python", "javascript",
            "react", "node", "spring", "boot", "sql", "database", "cloud", "aws", "azure", "docker",
            "kubernetes", "linux", "windows", "android", "ios", "app", "game", "movie", "series",
            "song", "music", "album", "artist", "lyrics", "recipe", "cook", "bake", "dinner", "lunch",
            "breakfast", "healthy", "diet", "workout", "yoga", "running", "shoes", "nike", "adidas",
            "puma", "jacket", "shirt", "jeans", "dress", "watch", "bag", "wallet", "sunglasses",
            "flight", "hotel", "ticket", "booking", "travel", "tour", "beach", "mountain", "city",
            "weather", "news", "stock", "crypto", "bitcoin", "ethereum", "price", "today", "tomorrow",
            "near", "map", "directions", "phone", "number", "login", "download", "free", "stream",
    };

    public static void main(String[] args) throws IOException {
        String path = args.length > 0 ? args[0] : "./data/queries.csv";
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 120_000;
        long seed = args.length > 2 ? Long.parseLong(args[2]) : 42L;
        int written = generate(Paths.get(path), size, seed);
        System.out.println("Wrote " + written + " queries to " + Paths.get(path).toAbsolutePath());
    }

    /** Generate only if the file does not already exist (used at app startup). */
    public static void generateIfMissing(Path path, int size, long seed) throws IOException {
        if (Files.exists(path)) return;
        generate(path, size, seed);
    }

    /** Generate the dataset and write it to {@code path}. Returns the number of rows written. */
    public static int generate(Path path, int targetDistinct, long seed) throws IOException {
        Random rnd = new Random(seed);

        // 1) Curated head (kept distinct, explicit high counts).
        Map<String, Long> data = new LinkedHashMap<>();
        for (String[] c : CURATED) {
            data.put(c[0], Long.parseLong(c[1]));
        }

        // 2) Synthesise the distinct long tail.
        Set<String> tail = new LinkedHashSet<>();
        long guard = 0, guardMax = (long) targetDistinct * 60;
        while (data.size() + tail.size() < targetDistinct && guard++ < guardMax) {
            String q = randomQuery(rnd);
            if (!q.isEmpty() && !data.containsKey(q)) {
                tail.add(q);
            }
        }

        // 3) Assign Zipf-like counts to the tail: shuffle, then count = BASE / rank^s.
        List<String> tailList = new ArrayList<>(tail);
        Collections.shuffle(tailList, rnd);
        final double base = 8000.0, s = 0.85;
        for (int i = 0; i < tailList.size(); i++) {
            int rank = i + 1;
            long count = Math.max(1, Math.round(base / Math.pow(rank, s)));
            data.put(tailList.get(i), count);
        }

        // 4) Write CSV (no commas appear in generated queries, so plain CSV is safe).
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("query,count");
            w.newLine();
            for (Map.Entry<String, Long> e : data.entrySet()) {
                w.write(e.getKey());
                w.write(',');
                w.write(Long.toString(e.getValue()));
                w.newLine();
            }
        }
        return data.size();
    }

    /** Build a 1-3 word query from the vocabulary (no repeated word within a query). */
    private static String randomQuery(Random rnd) {
        double r = rnd.nextDouble();
        int words = r < 0.20 ? 1 : (r < 0.80 ? 2 : 3);
        LinkedHashSet<String> picked = new LinkedHashSet<>();
        int attempts = 0;
        while (picked.size() < words && attempts++ < 10) {
            picked.add(VOCAB[rnd.nextInt(VOCAB.length)]);
        }
        return String.join(" ", picked);
    }
}
