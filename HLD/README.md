# Search Typeahead System

A search‑as‑you‑type application (like Google / Amazon autocomplete). As the user types, it suggests
popular queries; submitted searches update query popularity; suggestions are served from a distributed
cache and ranked by popularity and recency. The focus is the backend data‑system design.

## Features
- **Typeahead suggestions** — up to 10 prefix matches, sorted by search count (`GET /suggest`).
- **Search submission** — records the query and updates its count (`POST /search`).
- **Distributed cache with consistent hashing** — suggestions are cached across multiple logical nodes;
  a hash ring decides which node owns each prefix, with TTL and invalidation.
- **Trending + recency‑aware ranking** — recently searched queries get boosted; brief spikes decay out.
- **Batched writes** — search counts are buffered, aggregated, and flushed to the database in batches
  instead of writing on every request.
- **Metrics** — cache hit rate, DB read/write counts, and p50/p95 latency via `GET /stats`.

## Tech stack
Java 17 · Spring Boot 3.2 · H2 (file) · React + Vite. Dataset: 120,000 synthetic queries with
Zipf‑distributed counts (generated reproducibly).

## Architecture
A request to `GET /suggest` first checks the **distributed cache** (the owning node is chosen by
consistent hashing). On a miss it computes suggestions from an in‑memory **trie** (a prefix index over
the durable counts) and caches the result. `POST /search` does not write to the database directly — it
appends to an in‑memory **buffer**; a scheduled **batch writer** aggregates and flushes those counts to
**H2**. See project report for the full diagram.

```
React UI  →  Spring Boot API  →  Distributed cache (consistent hashing)
                               →  Trie index  →  H2 (durable counts)
                               →  Batch buffer → batch writer → H2
```

## Project structure
```
backend/    Spring Boot API (suggest, search, cache, trending, stats)
frontend/   React + Vite UI
docs/       design rationale, architecture, API, performance report
```

## Getting started

**Prerequisites:** Java 17, Maven, and Node.js 18+.

**1. Backend** (http://localhost:8080)
```bash
cd backend
mvn spring-boot:run
```
The first run generates the dataset (120k queries), loads it into H2, and builds the suggestion index.

**2. Frontend** (http://localhost:5173)
```bash
cd frontend
npm install
npm run dev
```

> On a machine without Java/Maven installed, a portable copy is bundled — activate it with
> `. .\tools\setenv.ps1` (PowerShell) before running `mvn`.

## API
| Endpoint | Description |
|---|---|
| `GET /suggest?q=<prefix>&ranking=basic\|recency` | Up to 10 suggestions for the prefix |
| `POST /search` `{ "query": "..." }` | Record a search; returns `{"message":"Searched"}` |
| `GET /cache/debug?prefix=<prefix>` | Which cache node owns the prefix, and hit/miss |
| `GET /trending` | Currently trending queries |
| `GET /stats` | Cache hit rate, DB read/write counts, latency percentiles |

Full reference: project report.

## Documentation
- Design & rationale — every design choice, trade‑offs, and Q&A
- Architecture · API · Performance report(refer all this in project report).

Configuration knobs (cache nodes, TTLs, batch size, recency weight, dataset size) live in
`backend/src/main/resources/application.yml`.

## Tests
```bash
cd backend
mvn test
```
