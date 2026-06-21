# API Reference

Base URL: `http://localhost:8080`. All responses are JSON.

---

## GET /suggest
Prefix‑matching suggestions, at most 10, sorted by the chosen ranking.

| Param | Type | Default | Notes |
|---|---|---|---|
| `q` | string | — | the prefix; empty/missing → `[]`; lower‑cased internally |
| `ranking` | `basic` \| `recency` | `recency` | basic = by count; recency = popularity + recent activity |

**200 OK**
```json
[
  { "query": "iphone", "count": 100000, "score": 100000.0 },
  { "query": "iphone 15", "count": 85000, "score": 85000.0 }
]
```
`score` is the value used to order the list (equals `count` in basic mode; the blended score in recency mode).

Examples:
```
GET /suggest?q=ip
GET /suggest?q=ip&ranking=basic
GET /suggest?q=IP            # mixed case → same as "ip"
GET /suggest?q=              # → []
GET /suggest?q=zzzz          # no match → []
```

---

## POST /search
Records a submitted search and returns a dummy response. The count update is **not** written to the DB
synchronously — it is buffered and flushed in a batch.

**Body**
```json
{ "query": "iphone" }
```
**200 OK**
```json
{ "message": "Searched" }
```
Behaviour: existing query → count increments on next flush; new query → inserted on next flush; the live
recency/trending signal updates immediately.

---

## GET /cache/debug
Shows which logical cache node owns a prefix (per the consistent‑hash ring) and whether it is cached.
Does not affect hit/miss metrics.

| Param | Type | Default |
|---|---|---|
| `prefix` | string | — |
| `ranking` | `basic` \| `recency` | `recency` |

**200 OK**
```json
{
  "prefix": "ip",
  "ranking": "recency",
  "ownerNode": "cache-node-2",
  "status": "MISS",
  "ringPositions": 450,
  "allNodes": ["cache-node-0", "cache-node-1", "cache-node-2"]
}
```

---

## GET /trending
Top queries by recency‑weighted activity in the sliding window.

| Param | Type | Default |
|---|---|---|
| `limit` | int | 10 |

**200 OK**
```json
[ { "query": "ipad mini deal", "score": 555.0 }, { "query": "iphone", "score": 9.0 } ]
```

---

## GET /stats
Everything for the performance report.

```json
{
  "cache":        { "hits": 587, "misses": 20, "hitRate": 0.967 },
  "db":           { "reads": 120000, "rowsWritten": 32, "flushes": 4 },
  "batchWrites":  { "searchSubmissions": 1561, "dbRowsWritten": 32,
                    "writeReductionRatio": 48.781, "writesSavedPercent": 97.95 },
  "suggestLatency": { "samples": 608, "p50Micros": 14, "p95Micros": 44, "p99Micros": 72 },
  "datasetSize": 120016,
  "consistentHashing": {
    "nodes": ["cache-node-0","cache-node-1","cache-node-2"],
    "ringPositions": 450,
    "currentEntriesPerNode": { "cache-node-0": 0, "cache-node-1": 0, "cache-node-2": 0 },
    "keyDistributionSample10k": { "cache-node-0": 3446, "cache-node-1": 2875, "cache-node-2": 3679 }
  }
}
```

---

## Admin / demo endpoints

| Method & path | Purpose |
|---|---|
| `POST /admin/flush` | Force a batch flush now → `{ "flushedRows": N }` |
| `POST /admin/cache/clear` | Empty all cache nodes |
| `POST /admin/node?action=add\|remove&id=<id>` | Add/remove a cache node and report `keysMovedPercent` (≈1/N) |

```json
// POST /admin/node?action=add&id=cache-node-3
{ "action": "add", "nodeId": "cache-node-3", "nodesNow": [ "...", "cache-node-3" ],
  "sampleKeys": 10000, "keysMoved": 2625, "keysMovedPercent": 26.25 }
```

The H2 web console is also available at `http://localhost:8080/h2-console`
(JDBC URL `jdbc:h2:file:./data/typeahead`, user `sa`, empty password).
