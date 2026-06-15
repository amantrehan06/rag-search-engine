package com.productsearch.pipeline;

import com.productsearch.infra.SessionManager;
import com.productsearch.model.ProductSearchRequest;
import com.productsearch.model.ProductSearchResponse;
import com.productsearch.model.ProductSearchSteps;
import com.productsearch.tracing.ProductPipelineTracer;
import io.opentelemetry.api.trace.StatusCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchPipeline {

    private final SessionManager sessionManager;
    private final ProductPipelineTracer tracer;
    private final IntentParseStage intentParseStage;
    private final QueryExpansionStage queryExpansionStage;
    private final CategoryRouteStage categoryRouteStage;
    private final HybridSearchStage hybridSearchStage;
    private final RerankStage rerankStage;
    private final RespondStage respondStage;
    @Qualifier("searchExpansionExecutor")
    private final Executor pipelineExecutor;

    public ProductSearchResponse processProductSearch(ProductSearchRequest request) {
        SearchContext ctx = SearchContext.of(request);
        try {
            sessionManager.addUserMessage(ctx.sessionId, request.getMessage());
            ctx.combinedQuery = sessionManager.getCombinedUserQuery(ctx.sessionId);

            ctx.rootSpan = tracer.startRootSpan(request.getMessage(), ctx.sessionId);
            ctx.rootScope = ctx.rootSpan.makeCurrent();

            // Intent parse and query expansion both only consume ctx.combinedQuery and write
            // to disjoint context fields (intent vs queryVariations). Fanning them out overlaps
            // the two LLM latencies — the total wall-clock becomes max(intent, expansion)
            // instead of intent + expansion. OTel context propagates to the workers via the
            // ContextPropagatingTaskDecorator on the executor, so both stage spans land as
            // children of the root span.
            CompletableFuture<Void> intentF    = CompletableFuture.runAsync(() -> intentParseStage.run(ctx),    pipelineExecutor);
            CompletableFuture<Void> expansionF = CompletableFuture.runAsync(() -> queryExpansionStage.run(ctx), pipelineExecutor);
            CompletableFuture.allOf(intentF, expansionF).join();

            if (ctx.response != null) return ctx.response;

            // [AGENT HOOK 1] confidence check after intent parse
            // future: if ctx.intentConfidence < 0.7, trigger clarification

            categoryRouteStage.run(ctx);

            // [AGENT HOOK 2] category match quality after category route
            // future: if ctx.categoryConfidence < 0.8, retry with rewritten query

            hybridSearchStage.run(ctx);

            // [AGENT HOOK 3] result count after hybrid search
            // future: if ctx.candidates.size() < 5, relax price filter and retry

            rerankStage.run(ctx);
            respondStage.run(ctx);
            return ctx.response;

        } catch (Exception e) {
            log.error("SearchPipeline failed: {}", e.getMessage(), e);
            closeSpanOnError(ctx, e);
            return ProductSearchResponse.builder()
                    .success(false)
                    .message("Error processing product search: " + e.getMessage())
                    .steps(ProductSearchSteps.builder().build())
                    .build();
        }
    }

    private static void closeSpanOnError(SearchContext ctx, Exception e) {
        if (ctx.rootSpan == null) return;
        try {
            ctx.rootSpan.recordException(e);
            ctx.rootSpan.setStatus(StatusCode.ERROR, e.getMessage());
            if (ctx.rootScope != null) ctx.rootScope.close();
            ctx.rootSpan.end();
        } catch (Exception ignored) {
            // tracing must never crash the user request
        }
    }
}
