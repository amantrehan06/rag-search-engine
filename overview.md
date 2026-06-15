# High-level overview

End-to-end request lifecycle for the product-search RAG pipeline.

## 1. App boot (Spring component scan)

| Bean | Built from | Purpose |
|---|---|---|
| `intentModel` | `openai.model.intent=gpt-4o-mini:0.0` | Function calling for intent extraction |
| `expansionModel` | `openai.model.expansion=gpt-4o-mini:0.7` | Tool-bound query rewriting |
| `rerankerModel` | `openai.model.reranker=gpt-4o-mini:0.0` | LLM rerank |
| `judgeModel` | `openai.model.judge=gpt-4o-mini:0.0` | Async eval scoring |
| `catalogModel` | `openai.model.catalog=gpt-4o-mini:0.7` | One-shot catalog description gen |
| `openAiEmbeddingModel` | `text-embedding-3-small` | Embeddings (ingest + query) |
| `searchExpansionExecutor` | `AsyncConfig`, core=3 max=9 + `ContextPropagatingTaskDecorator` | Pipeline-level parallelism + Pinecone fanout |
| `judgeExecutor` | core=2 max=4 + decorator | Async judge dispatch |
| `PineconeIndex` | `pinecone.index.name=at-ai-lab-...` | Singleton gRPC index connection |
| `CatalogMetadataProvider` | Empty on boot; reads `products.json` on first access | Provides brand/category lists for intent parser prompt |

## 2. Request enters

```
POST /api/v1/product-search/chat
{ "message": "...", "conversationId": "..." }

ProductSearchController.chatWithProductAssistant
        ↓
SearchPipeline.processProductSearch(request)
```

## 3. Pre-fanout (orchestrator, ~5 ms)

1. `SearchContext.of(request)` — resolves `sessionId` (conversationId > sessionId > UUID).
2. `sessionManager.addUserMessage(sessionId, request.message)` — appends to the session's `MessageWindowChatMemory` (cap 10).
3. `ctx.combinedQuery = sessionManager.getCombinedUserQuery(sessionId)` — joins all session user messages with `". "`.
4. `tracer.startRootSpan("product.search.process")` — root OTel span open, `Scope` made current on the calling thread.

## 4. Parallel fanout (orchestrator, ~max(intent, expansion))

```java
CompletableFuture<Void> intentF    = runAsync(() -> intentParseStage.run(ctx),    pipelineExecutor);
CompletableFuture<Void> expansionF = runAsync(() -> queryExpansionStage.run(ctx), pipelineExecutor);
allOf(intentF, expansionF).join();
```

Both stages capture the root span via `ContextPropagatingTaskDecorator` at submit time and re-establish it on the worker.

### 4a. `IntentParseStage` (worker thread)
- Opens `product.intent.parse` span as child of root.
- Builds tool schema from `CatalogMetadataProvider.Snapshot` (live brand/category lists).
- Calls `intentModel.chat(...)` with `ToolChoice.REQUIRED`.
- Extracts JSON args → `ProductSearchIntent`.
- Merges with `accumulator.get(sessionId)` (carries forward intent fields from earlier turns).
- Builds `IntentParserStep` into `ctx.stepsBuilder`.
- If validation fails (no category, no categorySearchQuery) → builds follow-up `ProductSearchResponse`, sets `ctx.response`.

### 4b. `QueryExpansionStage` (other worker thread, concurrent)
- If `variationCount == 1`: `ctx.queryVariations = [ctx.combinedQuery]`, returns. No LLM call, no span.
- Else: opens `product.query.expansion` span, calls `expansionModel.chat(...)` with tool-bound JSON schema (`{variations: [string × N]}`). Parses args → `List<String>`, writes to `ctx.queryVariations`.

After `join()`: if `ctx.response != null` (follow-up case from intent), orchestrator returns immediately.

## 5. Category routing (`CategoryRouteStage`, ~0–250 ms)

Three branches:

| Branch | When | Action |
|---|---|---|
| Direct | `intent.categoryId != null` | `ctx.resolvedCategoryId = intent.categoryId`, `categoryConfidence = 1.0`. No LLM, no Pinecone. |
| Semantic | `intent.categorySearchQuery != null` | Opens `product.category.search` span. `categoryPineconeService.searchCategoriesSemantically(q, 2)` → embeds + queries `categorynamespace`. First match ≥ 0.35 = `resolvedCategoryId`. `categoryConfidence = 1.0 / 0.7 / 0.0`. |
| Neither | nothing set | `categoryConfidence = 0.0`, `resolvedCategoryId = null` — hybrid search will short-circuit. |

Builds `CategorySearchStep` into `stepsBuilder`.

## 6. Hybrid search (`HybridSearchStage`, ~250–500 ms)

If `ctx.resolvedCategoryId == null`: emit empty `ProductSearchStep`, return.

Otherwise, `tracer.traceProductSearch(traceQuery, () -> { ... })` opens `product.hybrid.search` span and:

1. **`buildMetadataFilter(intent)`** — Pinecone filter map:
   - `category = $eq:<id>`
   - `price = $gte:min,$lte:max`
   - `brand_code = $eq` (single brand) or `$in:[...]` (multiple)
   - `free_shipping = $eq:"true"` if required
   - `warranty_included = $eq:"true"` if required

2. **`runSearches(ctx.queryVariations, filter)`** — returns `List<List<Match>>`:
   - **N=1**: direct serial call, single list.
   - **N>1**: N `CompletableFuture.supplyAsync` on `searchExpansionExecutor`. Each future:
     - `tracer.traceVariation(i, q, () -> pinecone.search(q, 10, filter))` opens `product.search.variation` sub-span as child of `product.hybrid.search`.
     - `pinecone.search` = embed query → `pineconeIndex.query(productsnamespace, vector, 10, 0.20, filter)`.
     - Returns top-10 matches for that variation.
   - `futures.stream().map(CompletableFuture::join).toList()` — per-query rankings preserved.

3. **`fuseRRF(perQuery)`** — Reciprocal Rank Fusion, k=60:
   - For each variation, each match at `rank r` contributes `1/(60 + r + 1)` to that match's productId score.
   - Sum across all variations; sort desc.
   - Returns single `List<Match>` consensus-ordered.

4. **OTel attributes set on the span:**
   - `search.query_variations` (e.g. 3)
   - `search.candidates_before_dedup` (raw, ≤30)
   - `search.candidates_after_dedup` (unique)
   - `search.fusion_method = "RRF"`
   - `search.candidates_in_all_variations` (consensus strong)
   - `search.candidates_in_majority` (consensus moderate)

5. **`ctx.candidates = fused`** (RRF-ordered).
6. **`ctx.products = toProducts(ctx.candidates)`** — Pinecone metadata → `ProductSearchResponse.Product` builder calls.
7. Build `ProductSearchStep` into stepsBuilder.

## 7. Rerank (`RerankStage`, ~400–800 ms)

If `ctx.products.isEmpty()` → empty `RerankResult`, return.

Otherwise, `tracer.traceReranker(() -> ...)` opens `product.reranker` span:

- `MAX_CANDIDATES_TO_RANK = 10` truncation — reranker sees top-10 by **RRF score**, not max cosine.
- `rerankerModel.chat(...)` with:
  - System prompt: "rank these from most to least relevant"
  - User message: `ctx.combinedQuery` + flat candidate list (id, name, brand, category, price, features)
- Parses returned ID list, looks each up in the candidate map, builds `List<Product>` in LLM order.
- Pads from RRF order if LLM returns fewer than topN (5).

Populates `ctx.rerankResult` (carries tokens/cost/timing) and `ctx.rankedResults` (top 5).

## 8. Respond (`RespondStage`, ~5 ms + async judge)

If `ctx.rankedResults` non-empty:

1. **Session cleanup** — `sessionManager.clearSession(sessionId)` + `intentParseStage.clear(sessionId)` (drop accumulator).
2. **Capture `traceId`** from `ctx.rootSpan`.
3. **`tracer.finishRootSpan(...)` with "Found N product(s) in <category>"** — root span closes, OTel batch processor will flush.
4. **`judge.evaluateAndSubmit(traceId, request.message, intent, rankedResults)`** — fires-and-forgets on `judgeExecutor`. Async path:
   - Sleeps 2s (lets batch span processor flush the trace to LangSmith).
   - Opens internal span (not tied to request trace since root is closed).
   - `judgeModel.chat(...)` returns JSON scores `{relevance, budget_adherence, intent_accuracy, summary}`.
   - `langSmithFeedbackService.submit(traceId, key, value, comment)` for each score — attaches feedback to the *original* request's LangSmith trace by id.
5. Build `ProductSearchResponse` with markdown summary (`appendIntent` + `appendCategory` + `appendProductSearch` from steps), set `ctx.response`.

If empty: emptyText path, similar but with the "no products found" copy.

## 9. Controller returns

`SearchPipeline` returns `ctx.response` → `ProductSearchController` → 200 OK to caller.

---

## OTel trace tree for one request

```
product.search.process                                    [root, ~2–3 s total]
├─ product.intent.parse                                   [parallel, ~700–1200 ms]
├─ product.query.expansion                                [parallel, ~600–900 ms]
├─ product.category.search                                [only if semantic, ~200 ms]
├─ product.hybrid.search                                  [≈ max(variation durations), ~250–500 ms]
│   ├─ product.search.variation #0  "ANC headphones for…" [180 ms, 8 matches]
│   ├─ product.search.variation #1  "premium audio for…"  [220 ms, 9 matches]
│   └─ product.search.variation #2  "headphones for focus" [310 ms, 7 matches]
└─ product.reranker                                       [~500 ms, 10 → 5]

(after root closes, fired async on judgeExecutor:)
└─ judge.evaluate → LangSmith Feedback API attaches scores to the above trace
```

## Knobs (`application.properties`)

```properties
server.port=${PORT:8080}

pinecone.index.name=at-ai-lab-index-openai-3-small

search.query_variations=3                               # 1 disables expansion entirely

openai.model.intent=gpt-4o-mini:0.0
openai.model.expansion=gpt-4o-mini:0.7
openai.model.reranker=gpt-4o-mini:0.0
openai.model.judge=gpt-4o-mini:0.0
openai.model.catalog=gpt-4o-mini:0.7
openai.model.embedding=text-embedding-3-small
```

Plus the hardcoded constants worth knowing about:

- `PER_VARIATION_LIMIT = 10` (`HybridSearchStage`)
- `RRF_K = 60` (`HybridSearchStage`)
- `MAX_CANDIDATES_TO_RANK = 10` (`ProductRerankerService`)
- Reranker `topN = 5` (passed from `RerankStage`)
- Pinecone min score = 0.20 (`ProductPineconeService`), 0.35 threshold for category (`CategoryPineconeService`)

## File map

```
pipeline/
  SearchPipeline.java          # orchestrator, parallel fanout, agent hook placeholders
  SearchContext.java           # shared mutable per-request state
  IntentParseStage.java        # intent parse + session accumulator + follow-up short-circuit
  QueryExpansionStage.java     # tool-bound LLM expansion
  CategoryRouteStage.java      # direct or semantic category resolution
  HybridSearchStage.java       # parallel Pinecone + RRF (no LLM calls)
  RerankStage.java             # LLM rerank top10 → top5
  RespondStage.java            # response text + async judge dispatch + root span close

config/
  OpenAiClientsConfig.java     # 5 chat models + embedding from properties (model:temp parsing)
  AsyncConfig.java             # judgeExecutor + searchExpansionExecutor + decorator
  ContextPropagatingTaskDecorator.java  # MDC + OTel context to worker threads
  OpenTelemetryConfig.java     # OTel SDK wiring

tracing/
  ProductPipelineTracer.java   # startRootSpan / finishRootSpan + 5 trace* methods

infra/
  PineconeIndex.java           # thin facade over io.pinecone SDK
  SecretManagerService.java    # env/property credential lookup
  CatalogMetadataProvider.java # brand/category list from products.json
  SessionManager.java          # per-session ChatMemory
  LLMCallResult.java           # generic LLM call envelope (result + tokens + timing)

service/
  ProductIntentParserService.java    # calls intentModel
  ProductRerankerService.java        # calls rerankerModel
  ProductPineconeService.java        # embed + query products namespace
  CategoryPineconeService.java       # embed + query category namespace + ingest
  ProductIngestionService.java       # reads products.json → upserts to Pinecone
  CatalogGenerationService.java      # reads YAML → calls catalogModel → writes products.json

evaluation/
  ProductJudgeService.java           # async LLM judge, posts feedback
  LangSmithFeedbackService.java      # OkHttp call to LangSmith Feedback API

controller/
  ProductSearchController.java       # thin HTTP front for SearchPipeline
  DataIngestionController.java       # endpoints for catalog/category/product ingestion
```
