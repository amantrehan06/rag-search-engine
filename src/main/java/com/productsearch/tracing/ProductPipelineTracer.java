package com.productsearch.tracing;

import com.productsearch.infra.LLMCallResult;
import com.productsearch.model.IntentParserResult;
import com.productsearch.model.RerankResult;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Component
public class ProductPipelineTracer {

    // Span names
    private static final String SPAN_ROOT            = "product.search.process";
    private static final String SPAN_INTENT_PARSE    = "product.intent.parse";
    private static final String SPAN_QUERY_EXPANSION = "product.query.expansion";
    private static final String SPAN_CATEGORY_SEARCH = "product.category.search";
    private static final String SPAN_HYBRID_SEARCH   = "product.hybrid.search";
    private static final String SPAN_VARIATION       = "product.search.variation";
    private static final String SPAN_RERANKER        = "product.reranker";

    // GenAI semantic convention attributes
    private static final String ATTR_GEN_AI_SYSTEM         = "gen_ai.system";
    private static final String ATTR_GEN_AI_OPERATION_NAME = "gen_ai.operation.name";
    private static final String ATTR_GEN_AI_REQUEST_MODEL  = "gen_ai.request.model";
    private static final String ATTR_GEN_AI_INPUT_TOKENS   = "gen_ai.usage.input_tokens";
    private static final String ATTR_GEN_AI_OUTPUT_TOKENS  = "gen_ai.usage.output_tokens";

    // Product-specific attributes
    private static final String ATTR_QUERY_TEXT              = "product.query.text";
    private static final String ATTR_SESSION_ID              = "product.session_id";
    private static final String ATTR_COST_USD                = "product.cost.usd";
    private static final String ATTR_DURATION_MS             = "product.duration_ms";
    private static final String ATTR_CATEGORY_QUERY          = "product.category.query";
    private static final String ATTR_CATEGORY_RESULTS_FOUND  = "product.category.results_found";
    private static final String ATTR_SEMANTIC_QUERY          = "product.search.semantic_query";
    private static final String ATTR_CANDIDATES_FOUND        = "product.search.candidates_found";
    private static final String ATTR_RERANKER_CANDIDATES_IN  = "product.reranker.candidates_in";
    private static final String ATTR_RERANKER_CANDIDATES_OUT = "product.reranker.candidates_out";

    // LangSmith metadata attributes
    private static final String ATTR_LS_SESSION_ID    = "langsmith.metadata.session_id";
    private static final String ATTR_LS_INPUT_TOKENS  = "langsmith.metadata.input_tokens";
    private static final String ATTR_LS_OUTPUT_TOKENS = "langsmith.metadata.output_tokens";
    private static final String ATTR_LS_COST_USD      = "langsmith.metadata.cost_usd";
    private static final String ATTR_LS_DURATION_MS   = "langsmith.metadata.duration_ms";

    // LangSmith input/output columns
    private static final String ATTR_INPUT_VALUE  = "input.value";
    private static final String ATTR_OUTPUT_VALUE = "output.value";

    // Variation attributes
    private static final String ATTR_VARIATION_INDEX   = "variation.index";
    private static final String ATTR_VARIATION_QUERY   = "variation.query";
    private static final String ATTR_VARIATION_MATCHES = "variation.matches";
    private static final String ATTR_EXPANSION_VARIATIONS_COUNT = "expansion.variations_count";

    // Cost rates (USD per token)
    private static final double MINI_COST_PER_INPUT_TOKEN  = 0.15 / 1_000_000.0;
    private static final double MINI_COST_PER_OUTPUT_TOKEN = 0.60 / 1_000_000.0;
    private static final double GPT4_COST_PER_INPUT_TOKEN  = 30.0 / 1_000_000.0;
    private static final double GPT4_COST_PER_OUTPUT_TOKEN = 60.0 / 1_000_000.0;

    private final Tracer tracer;

    public ProductPipelineTracer(@Qualifier("productTracer") Tracer tracer) {
        this.tracer = tracer;
    }

    public Span startRootSpan(String userMessage, String sessionId) {
        Span span = tracer.spanBuilder(SPAN_ROOT).startSpan();
        span.setAttribute(ATTR_QUERY_TEXT,   StringUtils.abbreviate(userMessage, 500));
        span.setAttribute(ATTR_SESSION_ID,   sessionId);
        span.setAttribute(ATTR_INPUT_VALUE,  userMessage);
        span.setAttribute(ATTR_LS_SESSION_ID, sessionId);
        return span;
    }

    public void finishRootSpan(Span span, Scope scope, String outputSummary) {
        span.setAttribute(ATTR_OUTPUT_VALUE, outputSummary);
        span.setStatus(StatusCode.OK);
        scope.close();
        span.end();
    }

    public IntentParserResult traceIntentParse(Supplier<IntentParserResult> work) {
        return traced(SPAN_INTENT_PARSE, s -> {}, work, (s, r) -> {
            llmAttrs(s, r.modelName(), r.inputTokens(), r.outputTokens(), r.durationMs());
            s.setAttribute(ATTR_INPUT_VALUE,  StringUtils.abbreviate(r.intent().getOriginalQuery(), 300));
            s.setAttribute(ATTR_OUTPUT_VALUE, r.rawJson());
        });
    }

    public LLMCallResult<List<String>> traceQueryExpansion(String baseQuery, Supplier<LLMCallResult<List<String>>> work) {
        return traced(SPAN_QUERY_EXPANSION, s -> {}, work, (s, r) -> {
            llmAttrs(s, r.modelName(), r.inputTokens(), r.outputTokens(), r.durationMs());
            s.setAttribute(ATTR_INPUT_VALUE,                StringUtils.abbreviate(baseQuery, 300));
            s.setAttribute(ATTR_OUTPUT_VALUE,               String.join(" | ", r.result()));
            s.setAttribute(ATTR_EXPANSION_VARIATIONS_COUNT, r.result().size());
        });
    }

    public List<String> traceCategorySearch(String query, Supplier<List<String>> work) {
        long start = System.currentTimeMillis();
        return traced(SPAN_CATEGORY_SEARCH,
                s -> { s.setAttribute(ATTR_CATEGORY_QUERY, StringUtils.abbreviate(query, 500));
                       s.setAttribute(ATTR_INPUT_VALUE,    StringUtils.abbreviate(query, 300)); },
                work,
                (s, r) -> { s.setAttribute(ATTR_CATEGORY_RESULTS_FOUND, r.size());
                            s.setAttribute(ATTR_DURATION_MS,             System.currentTimeMillis() - start);
                            s.setAttribute(ATTR_OUTPUT_VALUE,            r.isEmpty() ? "no categories found" : String.join(", ", r)); });
    }

    public int traceProductSearch(String semanticQuery, Supplier<Integer> work) {
        long start = System.currentTimeMillis();
        return traced(SPAN_HYBRID_SEARCH,
                s -> { s.setAttribute(ATTR_SEMANTIC_QUERY, StringUtils.abbreviate(semanticQuery, 500));
                       s.setAttribute(ATTR_INPUT_VALUE,    StringUtils.abbreviate(semanticQuery, 300)); },
                work,
                (s, c) -> { s.setAttribute(ATTR_CANDIDATES_FOUND, c);
                            s.setAttribute(ATTR_DURATION_MS,       System.currentTimeMillis() - start);
                            s.setAttribute(ATTR_OUTPUT_VALUE,      c + " candidate products found"); });
    }

    public <T> T traceVariation(int index, String variation, Supplier<T> work) {
        long start = System.currentTimeMillis();
        return traced(SPAN_VARIATION,
                s -> { s.setAttribute(ATTR_VARIATION_INDEX, index);
                       s.setAttribute(ATTR_VARIATION_QUERY, StringUtils.abbreviate(variation, 500)); },
                work,
                (s, r) -> { if (r instanceof Collection<?> c) s.setAttribute(ATTR_VARIATION_MATCHES, c.size());
                            s.setAttribute(ATTR_DURATION_MS, System.currentTimeMillis() - start); });
    }

    public RerankResult traceReranker(Supplier<RerankResult> work) {
        return traced(SPAN_RERANKER, s -> {}, work, (s, r) -> {
            llmAttrs(s, r.modelName(), r.inputTokens(), r.outputTokens(), r.durationMs());
            s.setAttribute(ATTR_RERANKER_CANDIDATES_IN,  r.candidatesIn());
            s.setAttribute(ATTR_RERANKER_CANDIDATES_OUT, r.candidatesOut());
            s.setAttribute(ATTR_INPUT_VALUE,  r.candidatesIn() + " candidates");
            s.setAttribute(ATTR_OUTPUT_VALUE, r.products().stream().limit(5)
                    .map(p -> p.getBrandName() + " " + p.getName() + " $" + (int) p.getPrice())
                    .collect(Collectors.joining(", ")));
        });
    }

    private <T> T traced(String name, Consumer<Span> pre, Supplier<T> work, BiConsumer<Span, T> post) {
        Span span = tracer.spanBuilder(name).startSpan();
        try (Scope ignored = span.makeCurrent()) {
            pre.accept(span);
            T result = work.get();
            post.accept(span, result);
            return result;
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }

    private static void llmAttrs(Span s, String rawModel, int in, int out, long durMs) {
        String model = rawModel == null ? null : rawModel.replaceAll("-\\d{4}-\\d{2}-\\d{2}$", "");
        double cost = cost(model, in, out);
        s.setAttribute(ATTR_GEN_AI_SYSTEM,         "openai");
        s.setAttribute(ATTR_GEN_AI_OPERATION_NAME, "chat");
        s.setAttribute(ATTR_GEN_AI_REQUEST_MODEL,  model);
        s.setAttribute(ATTR_GEN_AI_INPUT_TOKENS,   in);
        s.setAttribute(ATTR_GEN_AI_OUTPUT_TOKENS,  out);
        s.setAttribute(ATTR_COST_USD,              cost);
        s.setAttribute(ATTR_DURATION_MS,           durMs);
        s.setAttribute(ATTR_LS_INPUT_TOKENS,  String.valueOf(in));
        s.setAttribute(ATTR_LS_OUTPUT_TOKENS, String.valueOf(out));
        s.setAttribute(ATTR_LS_COST_USD,      String.format("$%.6f", cost));
        s.setAttribute(ATTR_LS_DURATION_MS,   durMs + "ms");
    }

    private static double cost(String model, int in, int out) {
        if (model == null) return 0.0;
        if (model.contains("gpt-4o-mini")) return in * MINI_COST_PER_INPUT_TOKEN + out * MINI_COST_PER_OUTPUT_TOKEN;
        return in * GPT4_COST_PER_INPUT_TOKEN + out * GPT4_COST_PER_OUTPUT_TOKEN;
    }
}
