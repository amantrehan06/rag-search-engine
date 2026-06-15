package com.productsearch.pipeline;

import com.productsearch.infra.SessionManager;
import com.productsearch.model.ProductSearchRequest;
import com.productsearch.model.ProductSearchResponse;
import com.productsearch.model.ProductSearchSteps;
import com.productsearch.model.RerankResult;
import com.productsearch.tracing.ProductPipelineTracer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.UUID;
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
        String sessionId = resolveSessionId(request);
        sessionManager.addUserMessage(sessionId, request.getMessage());
        String combinedQuery = sessionManager.getCombinedUserQuery(sessionId);

        Span rootSpan = tracer.startRootSpan(request.getMessage(), sessionId);
        Scope rootScope = rootSpan.makeCurrent();

        try {
            // Intent parse and query expansion both only consume combinedQuery and write to
            // disjoint result types. Fanning them out overlaps the two LLM latencies — total
            // wall-clock becomes max(intent, expansion) instead of intent + expansion.
            // OTel context propagates to workers via ContextPropagatingTaskDecorator so both
            // stage spans land as children of the root span.
            CompletableFuture<IntentResult>    intentF    = CompletableFuture.supplyAsync(
                    () -> intentParseStage.run(combinedQuery, sessionId, request.getMessage()), pipelineExecutor);
            CompletableFuture<ExpansionResult> expansionF = CompletableFuture.supplyAsync(
                    () -> queryExpansionStage.run(combinedQuery), pipelineExecutor);
            CompletableFuture.allOf(intentF, expansionF).join();

            IntentResult    intentResult    = intentF.join();
            ExpansionResult expansionResult = expansionF.join();

            if (intentResult.followUpResponse() != null) {
                tracer.finishRootSpan(rootSpan, rootScope, "Follow-up questions generated");
                return intentResult.followUpResponse();
            }

            // [AGENT HOOK 1] confidence check after intent parse
            // future: if intentResult.confidence() < 0.7, trigger clarification

            CategoryResult categoryResult = categoryRouteStage.run(intentResult.intent());

            // [AGENT HOOK 2] category match quality after category route
            // future: if categoryResult.confidence() < 0.8, retry with rewritten query

            SearchResult searchResult = hybridSearchStage.run(
                    categoryResult.resolvedCategoryId(), intentResult.intent(),
                    expansionResult.queryVariations(), combinedQuery);

            // [AGENT HOOK 3] result count after hybrid search
            // future: if searchResult.candidates().size() < 5, relax price filter and retry

            RerankResult rerankResult = rerankStage.run(combinedQuery, searchResult.products());

            ProductSearchSteps steps = ProductSearchSteps.builder()
                    .intentParser(intentResult.step())
                    .categorySearch(categoryResult.step())
                    .productSearch(searchResult.step())
                    .build();

            String traceId = rootSpan.getSpanContext().getTraceId();
            ProductSearchResponse response = respondStage.run(
                    sessionId, request.getMessage(), intentResult.intent(),
                    rerankResult.products(), steps, traceId);

            String summary = rerankResult.products().isEmpty() ? "No products found"
                    : "Found " + rerankResult.products().size() + " product(s) in "
                      + rerankResult.products().get(0).getCategory();
            tracer.finishRootSpan(rootSpan, rootScope, summary);

            intentParseStage.clear(sessionId);
            return response;

        } catch (Exception e) {
            log.error("SearchPipeline failed: {}", e.getMessage(), e);
            closeSpanOnError(rootSpan, rootScope, e);
            return ProductSearchResponse.builder()
                    .success(false)
                    .message("Error processing product search: " + e.getMessage())
                    .steps(ProductSearchSteps.builder().build())
                    .build();
        }
    }

    private static String resolveSessionId(ProductSearchRequest request) {
        if (request.getConversationId() != null && !request.getConversationId().isBlank())
            return request.getConversationId();
        if (request.getSessionId() != null && !request.getSessionId().isBlank())
            return request.getSessionId();
        return UUID.randomUUID().toString();
    }

    private static void closeSpanOnError(Span rootSpan, Scope rootScope, Exception e) {
        if (rootSpan == null) return;
        try {
            rootSpan.recordException(e);
            rootSpan.setStatus(StatusCode.ERROR, e.getMessage());
            if (rootScope != null) rootScope.close();
            rootSpan.end();
        } catch (Exception ignored) {
            // tracing must never crash the user request
        }
    }
}
