# Design & Rationale — Search Typeahead System

> This is the document to read before a viva/mock interview. It explains **what** was built,
> **why** each design choice was made, the **alternatives** considered, and the **trade-offs**.
> Every claim here maps to real code in this repo. A focused Q&A is at the end.

---

## 1. What the system does (one paragraph)

As the user types, the UI calls `GET /suggest?q=<prefix>` and shows up to 10 matching queries,
ranked either by all-time popularity (**basic**) or by a blend of popularity and recent activity
(**recency**). Submitting a search calls `POST /search`, which returns a dummy `{"message":"Searched"}`
and records the query **without writing to the database synchronously**. Suggestions are served from
an **in-memory trie** fronted by a **distributed cache** whose nodes are chosen by **consistent hashing**.
Recent searches feed a **trending** list and a **recency-aware ranking**. Writes are **buffered,
aggregated, and flushed in batches** to a durable **H2** store. `GET /stats` reports cache hit rate,
DB read/write counts, write-reduction ratio, and p50/p95 latency.

---

## 2. Requirement → implementation map

| Requirement | Where it lives |
|---|---|
| Dataset ≥100k with counts | `tools/DatasetGenerator.java` (120k, Zipf), loaded by `ingest/DatasetLoader.java` |
| `GET /suggest` (≤10, prefix, count-sorted, graceful) | `controller/SuggestController`, `service/SuggestionService`, `service/TrieIndex` |
| `POST /search` (dummy + count update) | `controller/SuggestController#search`, `service/batch/BatchWriteBuffer` |
| `GET /cache/debug` (node + hit/miss) | `controller/CacheDebugController`, `service/cache/DistributedCache#debug` |
| Cache before primary store, TTL, invalidation | `service/cache/DistributedCache`, `CacheNode` |
| Distributed cache + consistent hashing | `service/cache/ConsistentHashRing`, `CacheNode`, `DistributedCache` |
| Trending + recency ranking | `service/TrendingService`, `service/RankingService` |
| Batch writes (buffer, aggregate, flush, failure) | `service/batch/BatchWriteBuffer`, `BatchWriter` |
| Metrics: hit rate, DB counts, p95 | `service/metrics/MetricsService`, `controller/StatsController` |
| UI (debounce, keyboard, trending, states) | `frontend/src/**` |

---

## 3. Architecture & data flow

```
                React UI (Vite :5173)
   debounced /suggest · /search · /trending · /stats
                       │
                       ▼
            Spring Boot API (:8080)
   ┌───────────────────────────────────────────────────────┐
   │ READ  /suggest                                          │
   │   SuggestionService                                     │
   │     → DistributedCache.get(prefix, mode)                │
   │         consistent-hash ring → one CacheNode (TTL)      │
   │       hit  → return cached top-10                       │
   │       miss → TrieIndex.candidates(prefix)               │
   │              (+ trending matches in recency mode)       │
   │              → RankingService scores → top-10           │
   │              → cache.put(prefix, mode, result, TTL)     │
   ├───────────────────────────────────────────────────────┤
   │ WRITE /search                                           │
   │   BatchWriteBuffer.add(query)  (aggregates duplicates)  │
   │   TrendingService.record(query) (live recency signal)   │
   │   return {"message":"Searched"}                         │
   │                                                         │
   │   BatchWriter (every 2s / 500 distinct / on shutdown):  │
   │     drain buffer → TrieIndex.applyDeltas                │
   │     → JDBC batched MERGE into H2                         │
   │     → invalidate affected cache prefixes                │
   └───────────────────────────────────────────────────────┘
                       │
                       ▼
            H2 (file) — durable query→count
```

Two layers serve reads: the **distributed cache** (hot prefixes, O(1)) in front of the **trie index**
(the full dataset, O(prefix length)). The trie is the indexed, in-sync view of the durable H2 store:
built from H2 at startup, updated on each batch flush. Writes never touch H2 on the request path.

---

## 4. Design decisions, alternatives, and trade-offs

### 4.1 Tech stack
- **Java 17 + Spring Boot, React + Vite, H2 file DB.** Spring Boot gives DI, scheduling (`@Scheduled`),
  and JDBC batching with almost no boilerplate. H2 is a real SQL database that needs zero install and
  persists to a file, so counts survive restarts. React/Vite gives a fast dev server with a proxy so
  the browser is same-origin with the API.
- **Alternative:** SQLite (rejected — needs a third-party Hibernate dialect); Redis/Kafka for cache/queue
  (rejected for local simplicity — see §4.4).

### 4.2 Dataset: synthetic + Zipf
- A **seeded generator** produces 120,000 distinct queries; counts follow a **Zipf-like** law
  `count ≈ BASE / rank^0.85`. Real search popularity is extremely skewed (a few queries dominate), and
  Zipf reproduces that, which is what makes "sort by count" and the trending demo meaningful.
- A curated head (iphone family, java, etc.) gets explicit high counts so prefix demos look realistic.
- **Why synthetic:** real logs are private and awkward to ship; a fixed seed makes the dataset fully
  reproducible and offline. The loader can ingest any real `query,count` CSV instead — same pipeline.

### 4.3 Suggestion serving: in-memory Trie with cached top-K
- A **trie** (prefix tree) reaches a prefix's node in **O(prefix length)**, independent of dataset size.
- The expensive part of typeahead is *ranking the subtree*. Naively, a short prefix like "a" could have
  thousands of descendants. So **each node caches the top-N (50) queries in its subtree by count**,
  pre-sorted. A lookup just walks to the node and returns that list — **no subtree scan per keystroke**.
- **Build cost** is paid once at startup (~4s for 120k). Updates on flush walk only the changed queries'
  paths. Pruning (skip a query at a node whose top-N is already stronger) keeps build/update cheap.
- **Alternative:** SQL `LIKE 'p%' ORDER BY count LIMIT 10` per keystroke — rejected; far higher latency
  and DB load. The trie is the canonical autocomplete structure and the right viva answer.
- **Trade-off:** the trie holds the dataset in RAM (fine for 120k; for billions you'd suggest shard the
  trie by prefix or use a search index). Documented as a limitation in §7.

### 4.4 Distributed cache + consistent hashing
- The cache is **N logical nodes** (default 3), each an in-process map with **per-entry TTL**. A
  **`ConsistentHashRing`** maps a prefix to one node.
- **Why consistent hashing, not `hash % N`?** With `% N`, changing N (adding/removing a node) remaps
  *almost every* key and wipes the cache. Consistent hashing places nodes and keys on one circular hash
  space; a key is owned by the first node clockwise. Adding/removing a node only moves the keys in one
  arc — on average **~1/N**. Measured: adding a 4th node moved **26.25%** of 10k keys (≈1/4).
- **Virtual nodes (150/node):** without them, 3 random points on the ring give lumpy shares. 150 vnodes
  per node smooths the distribution. Measured split of 10k keys: **3446 / 2875 / 3679** (~even).
- **Hash function:** first 8 bytes of MD5 — deterministic and well-distributed (cryptographic strength is
  irrelevant here; we only need good spread).
- **Why in-process nodes, not real Redis?** The assignment asks for "multiple **logical** cache nodes."
  In-process nodes demonstrate distribution + consistent hashing exactly, with zero infra to run locally.
  Swapping each `CacheNode` for a Redis client is a drop-in change; the ring logic is unchanged.

### 4.5 Cache key, TTL, invalidation
- The **ring is keyed by the normalized prefix only** ("consistent hashing decides which node owns a
  prefix key"). Within a node, the slot key also includes the ranking mode (`BASIC|prefix` vs
  `RECENCY|prefix`) so both rankings live on the same node without colliding.
- **TTL:** 60s for basic (popularity changes slowly), **5s for recency** (rankings change fast). TTL
  guarantees staleness is bounded even with no explicit invalidation.
- **Explicit invalidation:** when a batch flush changes counts, the `BatchWriter` invalidates the cache
  entries for **every prefix of every updated query**, so a changed query's suggestion lists refresh
  immediately rather than waiting for TTL. TTL is the backstop; invalidation is the precise mechanism.
- **Normalization** (lowercase + trim) handles mixed-case and stray whitespace, so "IPhone " hits the
  same cache entry as "iphone".

### 4.6 Trending & recency (the 20% feature) — the four required points
A single mechanism, a **sliding window of time buckets**, answers all four:

1. **How recent searches are tracked.** Time is divided into fixed buckets (60s) over a window (10 min →
   10 buckets) arranged as a ring. Every `/search` increments the **current** bucket for that query,
   immediately (independent of the DB flush), so trending is live.
2. **How recent activity affects ranking.** `recentScore = Σ over live buckets (count × weight)` where
   **newer buckets weigh more** (linear: newest = 10, oldest = 1). Enhanced ranking score is
   `log10(count+1) + β·recentScore`. The historical term is **log-dampened** so a viral recent query can
   overtake an all-time-popular one; `β` (default 0.7) tunes how aggressive recency is.
3. **How we avoid permanently over-ranking a briefly-popular query.** A bucket older than the window is
   **reused (cleared) on the next lap**, so its contribution drops to zero. A query that spiked for one
   minute decays out of the window automatically — it cannot stay boosted forever. (Time decay without
   manual cleanup.)
4. **How the cache is updated when rankings change.** Short recency TTL (5s) + explicit prefix
   invalidation on flush (§4.5). So a reordering is reflected within seconds.

- **Trending list** = global top-K by `recentScore`. The suggestion engine in recency mode also unions in
  trending queries that match the prefix, so a surging *rare* query can appear even if it is not a
  top-by-count candidate of that prefix node. Demonstrated: "ipad mini deal" (count 0) jumped to #1 under
  `ranking=recency` after 60 searches, while staying absent under `ranking=basic`.
- **Alternative:** exponential decay per query (`score = score·e^(−λΔt)+1`). Equivalent idea; buckets are
  easier to explain and bound memory naturally.

### 4.7 Batch writes (the 20% feature)
- `/search` pushes into **`BatchWriteBuffer`**, a `ConcurrentHashMap<query, LongAdder>` that **aggregates
  duplicates**: 10 searches of "iphone" become one `+10`, not 10 writes.
- **`BatchWriter`** flushes (a) every 2s (`@Scheduled`), (b) when ≥500 distinct queries are buffered, and
  (c) on graceful shutdown (`@PreDestroy`). A `tryLock` ensures two flushes never overlap.
- A flush **drains** the buffer, applies deltas to the trie (getting new absolute counts), writes them to
  H2 in **one JDBC batched `MERGE`** (upsert), then invalidates affected cache prefixes.
- **Why absolute values, not SQL increments?** The trie holds the authoritative current count in memory,
  so the flush can write absolute values and avoid a read-modify-write round trip per row.
- **Write-reduction evidence:** measured **1561 submissions → 32 row-writes (48.8× reduction, 97.95% of
  writes avoided)**. `/stats.batchWrites` reports this live.
- **Failure trade-off (required discussion):** the buffer is in memory, so a **hard crash before a flush
  loses that window's increments** — we trade a little durability for far fewer writes. Mitigations:
  `@PreDestroy` flush covers graceful shutdown; production options are an **append-only WAL** of raw
  events (replay on restart), a **durable queue** (Kafka) instead of an in-memory map, or a **smaller
  flush interval** (less loss, more writes). The counts are also non-critical analytics, which justifies
  favouring throughput. There is also a tiny race where a search arriving during the atomic buffer swap
  lands in the next batch — acceptable for popularity counts.

### 4.8 Metrics & latency
- `MetricsService` keeps lock-free atomic counters and a **fixed-size ring buffer** of `/suggest`
  latencies; percentiles are computed on demand, so memory is bounded and there is no external load tool.
- Measured server-side suggest latency: **p50 = 14µs, p95 = 44µs, p99 = 72µs** (cache + trie are in-memory).

### 4.9 Concurrency model
- **Trie:** a single `ReadWriteLock` — many concurrent reads (`/suggest`), brief exclusive writes (flush).
  Lookups copy the small (≤50) candidate list so scoring happens outside the lock.
- **Cache:** `ConcurrentHashMap` per node; ring is **copy-on-write** (volatile snapshot) so the hot
  `getNode` path is lock-free and node add/remove never blocks reads.
- **Buffer:** `ConcurrentHashMap` + `LongAdder`; drain via `AtomicReference.getAndSet` (atomic swap).

---

## 5. Performance summary (see PERFORMANCE_REPORT.md for the run)

| Metric | Value |
|---|---|
| Dataset size | 120,000 queries |
| Trie build time | ~4.0 s (startup, once) |
| Suggest latency p50 / p95 / p99 | 14 µs / 44 µs / 72 µs |
| Cache hit rate (under repeated prefixes) | ~96.7% |
| Batch write reduction | 1561 submissions → 32 writes (48.8×, 97.95% saved) |
| Consistent-hash key spread (10k, 3 nodes) | 3446 / 2875 / 3679 |
| Keys remapped when adding a 4th node | 26.25% (≈1/4) |

---

## 6. Anticipated viva questions & answers

**Q: Why a trie instead of querying the database for each keystroke?**
A trie reaches a prefix in O(prefix length) and, because each node caches its subtree's top-10, a lookup
needs no scan and no DB hit. A SQL `LIKE` per keystroke would be slow and hammer the DB. The trie is the
classic autocomplete structure.

**Q: If the trie already serves everything in-memory, why have a cache at all?**
The cache is the **distributed** layer where consistent hashing is demonstrated and where, in a real
system, you avoid recomputing/ranking and avoid hitting a shared store. Treat the trie as the indexed
view of the primary store (the cache-miss "compute" path); the cache is the hot fast path in front of it.

**Q: Explain consistent hashing in one breath.**
Put nodes and keys on a circular hash space; a key is owned by the first node clockwise. Adding/removing a
node only reassigns the arc between it and its neighbour — about 1/N of keys — instead of reshuffling all
keys like `hash % N` would. Virtual nodes spread each physical node across many ring positions for even load.

**Q: How does recency ranking avoid a one-hour spike dominating forever?**
Recent activity lives in a sliding window of time buckets. Old buckets fall out of the window (are
cleared on the next lap), so a query's recency contribution decays to zero once it stops being searched.
Historical count is also log-dampened, so the blend is bounded.

**Q: What exactly is your ranking formula?**
Basic: `score = count`. Recency: `score = log10(count+1) + β·recentScore`, `β = 0.7`. `recentScore` is the
recency-weighted sum of searches in the last 10 minutes (newer minutes weigh more).

**Q: How do batch writes reduce DB load, and what do you lose?**
Searches are aggregated in memory and flushed periodically as one batched upsert, so N searches across K
distinct queries become ~K row-writes per flush instead of N writes. The cost is durability: a hard crash
before a flush loses the buffered increments. We flush on graceful shutdown and could add a WAL/durable
queue to harden it.

**Q: How is the cache kept correct when counts change?**
Two mechanisms: short TTL (5s recency, 60s basic) bounds staleness, and on each flush we explicitly
invalidate the cache entries for every prefix of every updated query.

**Q: What happens on `GET /suggest` with empty / mixed-case / unknown prefix?**
Empty → empty list. Mixed-case → normalized to lowercase, so it matches. Unknown prefix → the trie walk
hits a missing child and returns an empty list. All handled, none error.

**Q: Where could this break at scale, and how would you evolve it?**
Single-process, in-RAM trie and in-process cache. At scale: shard the trie by prefix across services,
replace logical cache nodes with a Redis cluster (same ring), move the buffer to Kafka with a consumer
writing batches, and store counts in a horizontally scalable store. The interfaces here map 1:1 to those.

**Q: Why MD5 for hashing — isn't that for security?**
We only use it as a fast, well-distributed hash to place keys/vnodes on the ring; cryptographic strength
is irrelevant. Any good hash (Murmur, xxHash) would do.

---

## 7. Known limitations (honest list)
- In-memory trie + in-process cache ⇒ single-node; not horizontally scalable as-is (see §6 evolution).
- Crash before flush loses buffered counts (intentional throughput/durability trade-off; §4.7).
- Trending candidate union is approximate for very deep prefixes (bounded by the trending top set size).
- H2 file mode is single-writer; fine for a demo, not for production write throughput.
