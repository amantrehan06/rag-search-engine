# RAG Search Engine — System Flow

This document explains how the system processes a natural-language product
query into a ranked list of real products. There are two completely separate
phases: ingestion (runs before any query) and query execution (runs per
user request).

---

## User Query Example

> "Looking for premium noise-cancelling headphones under $400 with free shipping."

---

## Phase 1: Ingestion (Runs Once, Not Per Query)

Before any user can search, two separate data stores are populated.
This is pre-computation — the work that makes fast query execution possible.

### 1A. Category Namespace (CategorySemanticDB)

Pre-loaded once. Updated only when new categories are opened (months, not minutes).

**What gets stored:**
Each category is represented as a rich editorial description — not just a name.

```
"Premium audio equipment for music enthusiasts, professionals, and everyday
listeners. Wireless headphones, earbuds, and speakers designed for commuting,
focused work, gaming, and home entertainment with industry-leading sound
quality."
```

**How it's stored:**
- Text description → embedded via OpenAI embedding model → stored as a vector
- Category id (e.g. `audio`) stored as metadata alongside the vector
- Namespace: `categorynamespace` in Pinecone

**Current dataset:**
3 categories — `audio`, `footwear`, `furniture`.
Each with a distinct editorial description so that vague queries like
"comfortable for my home office" or "something for marathon training"
map reliably to the right category id.

**Why separate from products:**
Category descriptions are stable vocabulary. The embedding for "premium audio
equipment" doesn't change because a new SKU launched. Keeping categories in
their own namespace means product price/stock updates never touch this corpus.

---

### 1B. Products Namespace (ProductSemanticDB)

Populated by `CuratedProductDataGenerator`. Updated when new product data is
seeded or refreshed.

**What gets stored per product:**
Each product is represented as a use-case description plus structured fields:

```
"Product: Sony WH-1000XM4 — Black. Category: audio. Brand: Sony. Tier: Premium.
Industry-leading noise cancellation with adaptive sound control for music,
calls, and focused work. 30-hour battery, touch controls, and lightweight build
make this a trusted daily commuter and home office companion. Features: free
shipping, bundle included."
```

**How it's stored:**
- Use-case text → embedded → stored as a vector
- Structured metadata stored alongside the vector:
  - `id` (e.g. `prod_SONY_Premium_03`)
  - `name` (e.g. "Sony WH-1000XM4 — Black")
  - `category` (e.g. `audio`)
  - `brand_code` (e.g. `SONY`)
  - `brand_name` (e.g. "Sony")
  - `tier_name` (`Basic` / `Premium` / `Pro`)
  - `price` (numeric — preserved as a number for `$lt`/`$gte` range filters)
  - `description` (the un-prefixed copy)
  - `variant_label` (e.g. "Black", "Wide M11", "Bundle - With Case")
  - `free_shipping` (boolean as string)
  - `warranty_included` (boolean as string)
  - `bundle_included` (boolean as string)
- Namespace: `productsnamespace` in Pinecone

**Current dataset:**
252 products. 3 categories × 2 brands × 3 tiers × 14 variants. Prices
deterministic per `(brand, tier)` — not random. Defined in
`curated-product-rules.yaml` with specific variant-level overrides for eval
coverage (e.g. Sony WH-1000XM5 variantIndex 9 is $419 — intentionally crosses
the $400 threshold to test that the price filter is load-bearing).

**Key design: reuse vector, update metadata only**
If a product's price changes but its use-case description doesn't, there is no
need to re-embed. The embedding encodes what the product *is* (brand, tier,
features, intended use). The price is metadata. Only the metadata upsert is
needed. This avoids unnecessary embedding API calls on price changes —
significant when the catalog moves to streaming updates.

---

## Phase 2: Query Execution (Per User Request)

Five stages run in sequence for every search.
Each stage is an OpenTelemetry span — latency, tokens, and cost are
measured independently and visible in LangSmith traces.

---

### Stage 1: Intent Parsing (product.intent.parse)

**What happens:**
The user's natural-language query is sent to OpenAI using function calling.
The model is constrained to return a typed JSON object — it cannot return
free text.

**LLM:** gpt-4o-mini (configurable via env var `OPENAI_FUNCTION_MODEL`)
**Span cost example:** ~$0.0003 per call at gpt-4o-mini pricing

**Output — ProductSearchIntent:**
```json
{
  "categoryId": "audio",
  "brandCode": null,
  "productTier": "Premium",
  "categorySearchQuery": null,
  "productSearchQuery": "Premium noise-cancelling over-ear headphones for
                         daily commuting and focused work",
  "freeShippingRequired": true,
  "warrantyRequired": null,
  "bundleRequired": null,
  "maxPrice": 400.0,
  "minPrice": null,
  "confidence": 0.95,
  "clarificationNeeded": false
}
```

**Key design decisions:**

Two synthesised query fields instead of raw user text:
- `categorySearchQuery` — normalised description of the category vibe.
  Set ONLY when the user didn't name a category. Used to search the category
  namespace.
- `productSearchQuery` — normalised description of the ideal product.
  Always set. Used to rank within the products namespace.

Normalisation makes embedding more predictable than embedding the raw query
directly.

Function calling vs prompt-and-parse:
Function calling makes invalid outputs impossible — the OpenAI API enforces
schema compliance before the response leaves their servers. Prompt-and-regex
extraction fails silently on edge cases (unexpected phrasing, missing field).

Hard-flag discipline:
`freeShippingRequired`, `warrantyRequired`, `bundleRequired` are tri-valued:
`true` (user explicitly required), `false` (almost never set — the LLM is
told to leave null when not mentioned), or `null` (default — no filter
applied). Defaulting to `false` instead of `null` would silently filter
out all premium products the moment a casual user typed "shoes" without
mentioning shipping.

Missing field validation:
If both `categoryId` and `categorySearchQuery` are empty, the system returns
a clarification prompt rather than proceeding with an unrooted intent.
"What kind of product are you looking for?" is returned to the user.

**OTel span:** `product.intent.parse`
Records: model name, input tokens, output tokens, cost_usd.

---

### Stage 2: Category Search (product.category.search)

**Condition:** Only runs if the user gave a vibe ("comfortable for my home
office"), not a specific category. If `categoryId` is already populated in
the intent, this stage is skipped and the category id goes directly to
Stage 3 as a metadata filter.

**What happens:**
The `categorySearchQuery` from Stage 1 is embedded and used to search the
`categorynamespace` in Pinecone. Returns the N most similar category vectors
above a 0.35 similarity threshold.

**Output:** List of category ids
```
["furniture"]
```

The top id becomes the `category: {$eq: "..."}` metadata filter in Stage 3.

**Why this stage exists:**
"Comfortable for my home office" has no direct match in a product database.
No SKU is named "home office." This stage translates vague user intent into
a concrete category id the metadata filter can use, separating semantic
matching (against ~3 category descriptions) from the much larger product
ranking problem (against 252 product descriptions).

**OTel span:** `product.category.search`
Records: latency, number of results.

---

### Stage 3: Hybrid Product Search (product.hybrid.search)

**What happens:**
A single Pinecone call that combines two constraints:

```java
pineconeEmbeddingStore.findRelevantProducts(
    queryEmbedding,     // from productSearchQuery
    maxResults,         // up to 20 candidates
    0.20,               // minimum similarity floor
    metadataFilter      // structured constraints
);
```

**Metadata filter (structured layer):**
```json
{
  "category":      { "$eq": "audio" },
  "tier_name":     { "$eq": "Premium" },
  "price":         { "$gte": 0.0, "$lte": 400.0 },
  "free_shipping": { "$eq": "true" }
}
```

Handles: price range, category, brand, tier, hard feature booleans. Boolean
filters are only applied when the parser detected the user *required* the
feature — not just mentioned it.

**Vector query (semantic layer):**
The `productSearchQuery` embedding ranks results by use-case fit.
"Premium noise-cancelling over-ear headphones for daily commuting and
focused work" surfaces Sony WH-1000XM4 / WH-1000XM5 above the lighter
Sony WH-CH520 — even among products that all match the category and
price filter.

**Why hybrid, not pure vector or pure SQL:**

Pure vector fails on structured constraints:
The embedding for "premium feel under $400" doesn't know what $400 means.
Vector models have no number sense — a $1799 leather sofa scores high on
"premium" and appears in results even though it violates the budget.

Pure SQL fails on semantic constraints:
`SELECT WHERE price <= 400` works. `SELECT WHERE "for daily commuting and
focused work"` doesn't — use-case fit is continuous, SQL is categorical.

Hybrid handles both: metadata filter enforces hard constraints, vector
search ranks by semantic preference. Both constraints apply to the same row.

**Similarity floor of 0.20:**
Deliberately permissive. Filters out completely irrelevant results without
being so strict that valid matches are discarded. The reranker (Stage 4)
handles fine-grained ranking among survivors.

**OTel span:** `product.hybrid.search`
Records: latency, number of candidates returned.

---

### Stage 4: Reranker (product.reranker)

**What happens:**
The top candidates from Stage 3 (up to 10) are re-scored by an LLM using
the full user query as context.

**LLM:** gpt-4o-mini
**Input cap:** 10 candidates (`MAX_CANDIDATES_TO_RANK = 10`)
**Output:** top 5 reranked products

**Why cap at 10:**
Passing all 20 Pinecone candidates roughly doubles prompt tokens for diminishing
ranking signal — the bottom half of Pinecone's similarity-ordered pool is
rarely promoted. Capping at 10 keeps prompts tight and per-call latency low.
The Pinecone similarity ordering is a good-enough pre-filter.

**What the reranker adds:**
Pinecone ranks by vector similarity to the `productSearchQuery` embedding.
The reranker re-scores with the full user query as context — it can weight
factors the embedding doesn't capture perfectly, like implicit "for travel
vs office" cues, "best value vs flagship at any price" signals, or brand
preference signals embedded in the phrasing.

**OTel span:** `product.reranker`
Records: model name, input tokens, output tokens, cost_usd, latency.

---

### Stage 5: Response + Async Evaluation

**Response:**
The top 5 reranked products are returned to the user with a process summary
("Search Complete. Found 5 products in audio.").

**Async evaluation (does not block the response):**
A separate thread pool (core 2, max 4, queue 50) evaluates the completed
search against three metrics and posts scores back to LangSmith:

- `budget_adherence` — are all returned products within the stated budget?
  Deterministic check. Example: 1.00 (all products under $400).
- `intent_accuracy` — did the intent parser correctly extract all fields
  (category, brand, tier, price, required features)? LLM-as-judge.
- `relevance` — do the returned products match the user's described
  use case? LLM-as-judge.

Feedback is best-effort: if the process dies mid-evaluation, the score is
lost. Acceptable for a monitoring use case — missing a score doesn't
degrade the product.

---

## Approach comparison

| Concern | Vector-only | SQL + Vector (hybrid) |
|---|---|---|
| "Under $400" enforcement | Soft — high-priced items can leak | Hard — `price=$lte:400` excludes them |
| "Premium feel" ranking | Strong — embeddings capture use-case fit | Strong — vector layer still active |
| Free-shipping filter | Soft — model has to infer from text | Hard — boolean metadata filter |
| Out-of-catalog query | Returns nearest neighbours regardless | Returns empty when filters don't match |
| Inventory updates | Re-embed on every price change | Metadata-only upsert; vector unchanged |

---

## End-to-End Latency Breakdown (typical, gpt-4o-mini)

| Stage | Span | Order-of-magnitude latency |
|---|---|---|
| Intent parsing | `product.intent.parse` | ~2–3 s |
| Category search | `product.category.search` | ~1–2 s (only on vibe queries) |
| Hybrid product search | `product.hybrid.search` | ~1–2 s |
| Reranker | `product.reranker` | ~1 s |
| **Total** | `product.search.process` | **~5–8 s** |

Cost per request: ~$0.0004 (gpt-4o-mini) vs ~$0.05 (gpt-4) — observed
from real traces, not estimated from list prices.

---

## Evaluation Layer

Two eval suites run against the full pipeline (no mocks):

**`ProductSearchEvalTest` — 8 deterministic cases**
Each asserts on a specific business outcome against the known dataset.
Example: Eval 2 queries "premium headphones under $400" and asserts that
the Sony WH-1000XM5 variantIndex=9 (priced at exactly $419 by
`variantOverrides`) is **excluded**. Four such engineered price boundaries
exist in the YAML, each crossing a likely query threshold by a small margin
($1 – $29) to force the eval suite to exercise filter precision rather than
fuzz luck.

**`ProductRagasEvalTest` — 8 parameterized cases, 3 metrics**
Faithfulness (≥ 0.90), Answer Relevancy (≥ 0.70), Context Precision (≥ 0.80).
Faithfulness is deterministic (constraint check). Answer Relevancy uses an
LLM judge. Aggregate thresholds gate the build — a failing PR means a
metadata filter or reranker regressed.

Removing the `price` filter from `ProductSearchServiceImpl.buildMetadataFilter`
allows ADV-1 ($419) to leak into the "under $400" case → Faithfulness for
that case drops from 1.00 → 0.00 → aggregate crosses below 0.90 → build
fails → PR rejected. That is the test the eval layer exists to be.
