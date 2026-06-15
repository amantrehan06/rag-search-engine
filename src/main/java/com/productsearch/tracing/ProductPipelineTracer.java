package com.productsearch.tracing;

import com.productsearch.infra.LLMCallResult;
import com.productsearch.model.IntentParserResult;
import com.productsearch.model.RerankResult;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ProductPipelineTracer {

    private static final AttributeKey<String> GEN_AI_SYSTEM           = AttributeKey.stringKey("gen_ai.system");
    private static final AttributeKey<String> GEN_AI_OPERATION_NAME   = AttributeKey.stringKey("gen_ai.operation.name");
    private static final AttributeKey<String> GEN_AI_REQUEST_MODEL    = AttributeKey.stringKey("gen_ai.request.model");
    private static final AttributeKey<Long>   GEN_AI_INPUT_TOKENS     = AttributeKey.longKey("gen_ai.usage.input_tokens");
    private static final AttributeKey<Long>   GEN_AI_OUTPUT_TOKENS    = AttributeKey.longKey("gen_ai.usage.output_tokens");

    private static final AttributeKey<Double> COST_USD                 = AttributeKey.doubleKey("product.cost.usd");
    private static final AttributeKey<Long>   DURATION_MS              = AttributeKey.longKey("product.duration_ms");
    private static final AttributeKey<String> QUERY_TEXT               = AttributeKey.stringKey("product.query.text");
    private static final AttributeKey<String> SESSION_ID               = AttributeKey.stringKey("product.session_id");
    private static final AttributeKey<String> CATEGORY_QUERY           = AttributeKey.stringKey("product.category.query");
    private static final AttributeKey<Long>   CATEGORY_RESULTS_FOUND   = AttributeKey.longKey("product.category.results_found");
    private static final AttributeKey<String> PRODUCT_SEMANTIC_QUERY   = AttributeKey.stringKey("product.search.semantic_query");
    private static final AttributeKey<Long>   PRODUCT_CANDIDATES_FOUND = AttributeKey.longKey("product.search.candidates_found");
    private static final AttributeKey<Long>   RERANKER_CANDIDATES_IN   = AttributeKey.longKey("product.reranker.candidates_in");
    private static final AttributeKey<Long>   RERANKER_CANDIDATES_OUT  = AttributeKey.longKey("product.reranker.candidates_out");

    private static final AttributeKey<String> LS_INPUT_TOKENS  = AttributeKey.stringKey("langsmith.metadata.input_tokens");
    private static final AttributeKey<String> LS_OUTPUT_TOKENS = AttributeKey.stringKey("langsmith.metadata.output_tokens");
    private static final AttributeKey<String> LS_COST_USD      = AttributeKey.stringKey("langsmith.metadata.cost_usd");
    private static final AttributeKey<String> LS_DURATION_MS   = AttributeKey.stringKey("langsmith.metadata.duration_ms");

    private static final AttributeKey<String> INPUT_VALUE  = AttributeKey.stringKey("input.value");
    private static final AttributeKey<String> OUTPUT_VALUE = AttributeKey.stringKey("output.value");

    private static final double GPT4_COST_PER_INPUT_TOKEN  = 30.00 / 1_000_000.0;
    private static final double GPT4_COST_PER_OUTPUT_TOKEN = 60.00 / 1_000_000.0;

    private static final double MINI_COST_PER_INPUT_TOKEN  = 0.15 / 1_000_000.0;
    private static final double MINI_COST_PER_OUTPUT_TOKEN = 0.60 / 1_000_000.0;

    private final Tracer tracer;

    @Autowired
    public ProductPipelineTracer(Tracer productTracer) {
        this.tracer = productTracer;
    }

    public Span startRootSpan(String userMessage, String sessionId) {
        Span span = tracer.spanBuilder("product.search.process").startSpan();
        span.setAttribute(QUERY_TEXT,   StringUtils.abbreviate(userMessage, 500));
        span.setAttribute(SESSION_ID,   sessionId);
        span.setAttribute(INPUT_VALUE,  userMessage);   // → LangSmith Input column
        span.setAttribute(AttributeKey.stringKey("langsmith.metadata.session_id"), sessionId);

        log.info("OTel trace started — traceId={} sessionId={} query='{}'",
                span.getSpanContext().getTraceId(), sessionId, StringUtils.abbreviate(userMessage, 80));
        return span;
    }

    public void finishRootSpan(Span span, Scope scope, String outputSummary) {
        span.setAttribute(OUTPUT_VALUE, outputSummary);  // → LangSmith Output column
        span.setStatus(StatusCode.OK);
        scope.close();
        span.end();
    }

    public IntentParserResult traceIntentParse(Supplier<IntentParserResult> work) {
        Span span = tracer.spanBuilder("product.intent.parse").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            IntentParserResult result = work.get();
            String model = normalizeModelName(result.modelName());
            double cost  = calculateCost(model, result.inputTokens(), result.outputTokens());
            span.setAttribute(GEN_AI_SYSTEM,         "openai");
            span.setAttribute(GEN_AI_OPERATION_NAME, "chat");
            span.setAttribute(GEN_AI_REQUEST_MODEL,  model);
            span.setAttribute(GEN_AI_INPUT_TOKENS,   (long) result.inputTokens());
            span.setAttribute(GEN_AI_OUTPUT_TOKENS,  (long) result.outputTokens());
            span.setAttribute(COST_USD,              cost);
            span.setAttribute(DURATION_MS,           result.durationMs());
            span.setAttribute(LS_INPUT_TOKENS,  String.valueOf(result.inputTokens()));
            span.setAttribute(LS_OUTPUT_TOKENS, String.valueOf(result.outputTokens()));
            span.setAttribute(LS_COST_USD,      String.format("$%.6f", cost));
            span.setAttribute(LS_DURATION_MS,   String.valueOf(result.durationMs()) + "ms");

            span.setAttribute(INPUT_VALUE,  StringUtils.abbreviate(result.intent().getOriginalQuery(), 300));
            span.setAttribute(OUTPUT_VALUE, result.rawJson());
            return result;
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }

    public List<String> traceCategorySearch(String categoryQuery, Supplier<List<String>> work) {
        Span span = tracer.spanBuilder("product.category.search").startSpan();
        long start = System.currentTimeMillis();
        try (Scope ignored = span.makeCurrent()) {
            span.setAttribute(CATEGORY_QUERY, StringUtils.abbreviate(categoryQuery, 500));
            span.setAttribute(INPUT_VALUE,    StringUtils.abbreviate(categoryQuery, 300));
            List<String> categories = work.get();
            span.setAttribute(CATEGORY_RESULTS_FOUND, (long) categories.size());
            span.setAttribute(DURATION_MS,            System.currentTimeMillis() - start);
            span.setAttribute(OUTPUT_VALUE,
                    categories.isEmpty() ? "no categories found" : String.join(", ", categories));
            return categories;
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }

    public LLMCallResult<List<String>> traceQueryExpansion(String baseQuery,
                                                            Supplier<LLMCallResult<List<String>>> work) {
        Span span = tracer.spanBuilder("product.query.expansion").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            LLMCallResult<List<String>> result = work.get();
            String model = normalizeModelName(result.modelName());
            double cost  = calculateCost(model, result.inputTokens(), result.outputTokens());

            span.setAttribute(GEN_AI_SYSTEM,         "openai");
            span.setAttribute(GEN_AI_OPERATION_NAME, "chat");
            span.setAttribute(GEN_AI_REQUEST_MODEL,  model);
            span.setAttribute(GEN_AI_INPUT_TOKENS,   (long) result.inputTokens());
            span.setAttribute(GEN_AI_OUTPUT_TOKENS,  (long) result.outputTokens());
            span.setAttribute(COST_USD,              cost);
            span.setAttribute(DURATION_MS,           result.durationMs());
            span.setAttribute(LS_INPUT_TOKENS,  String.valueOf(result.inputTokens()));
            span.setAttribute(LS_OUTPUT_TOKENS, String.valueOf(result.outputTokens()));
            span.setAttribute(LS_COST_USD,      String.format("$%.6f", cost));
            span.setAttribute(LS_DURATION_MS,   result.durationMs() + "ms");

            span.setAttribute(INPUT_VALUE,  StringUtils.abbreviate(baseQuery, 300));
            span.setAttribute(OUTPUT_VALUE, String.join(" | ", result.result()));
            span.setAttribute("expansion.variations_count", (long) result.result().size());
            return result;
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }

    public <T> T traceVariation(int index, String variation, Supplier<T> work) {
        Span span = tracer.spanBuilder("product.search.variation").startSpan();
        long start = System.currentTimeMillis();
        try (Scope ignored = span.makeCurrent()) {
            span.setAttribute("variation.index", (long) index);
            span.setAttribute("variation.query", StringUtils.abbreviate(variation, 500));
            T result = work.get();
            if (result instanceof java.util.Collection<?> c) {
                span.setAttribute("variation.matches", (long) c.size());
            }
            span.setAttribute(DURATION_MS, System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }

    public int traceProductSearch(String semanticQuery, Supplier<Integer> work) {
        Span span = tracer.spanBuilder("product.hybrid.search").startSpan();
        long start = System.currentTimeMillis();
        try (Scope ignored = span.makeCurrent()) {
            span.setAttribute(PRODUCT_SEMANTIC_QUERY, StringUtils.abbreviate(semanticQuery, 500));
            span.setAttribute(INPUT_VALUE,            StringUtils.abbreviate(semanticQuery, 300));
            int count = work.get();
            span.setAttribute(PRODUCT_CANDIDATES_FOUND, (long) count);
            span.setAttribute(DURATION_MS,              System.currentTimeMillis() - start);
            span.setAttribute(OUTPUT_VALUE,             count + " candidate products found");
            return count;
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }

    public RerankResult traceReranker(Supplier<RerankResult> work) {
        Span span = tracer.spanBuilder("product.reranker").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            RerankResult result = work.get();
            String model = normalizeModelName(result.modelName());
            double cost  = calculateCost(model, result.inputTokens(), result.outputTokens());

            span.setAttribute(GEN_AI_SYSTEM,           "openai");
            span.setAttribute(GEN_AI_OPERATION_NAME,   "chat");
            span.setAttribute(GEN_AI_REQUEST_MODEL,    model);
            span.setAttribute(GEN_AI_INPUT_TOKENS,     (long) result.inputTokens());
            span.setAttribute(GEN_AI_OUTPUT_TOKENS,    (long) result.outputTokens());
            span.setAttribute(COST_USD,                cost);
            span.setAttribute(DURATION_MS,             result.durationMs());
            span.setAttribute(RERANKER_CANDIDATES_IN,  (long) result.candidatesIn());
            span.setAttribute(RERANKER_CANDIDATES_OUT, (long) result.candidatesOut());

            span.setAttribute(LS_INPUT_TOKENS,  String.valueOf(result.inputTokens()));
            span.setAttribute(LS_OUTPUT_TOKENS, String.valueOf(result.outputTokens()));
            span.setAttribute(LS_COST_USD,      String.format("$%.6f", cost));
            span.setAttribute(LS_DURATION_MS,   String.valueOf(result.durationMs()) + "ms");

            span.setAttribute(INPUT_VALUE,  result.candidatesIn() + " candidates");
            span.setAttribute(OUTPUT_VALUE, result.products().stream()
                    .limit(5)
                    .map(p -> p.getBrandName() + " " + p.getName() + " $" + (int) p.getPrice())
                    .collect(Collectors.joining(", ")));
            return result;
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }

    public double calculateCost(String modelName, int inputTokens, int outputTokens) {
        if (modelName == null) return 0.0;
        if (modelName.contains("gpt-4o-mini")) {
            return (inputTokens * MINI_COST_PER_INPUT_TOKEN) + (outputTokens * MINI_COST_PER_OUTPUT_TOKEN);
        }

        return (inputTokens * GPT4_COST_PER_INPUT_TOKEN) + (outputTokens * GPT4_COST_PER_OUTPUT_TOKEN);
    }

    String normalizeModelName(String modelName) {
        if (modelName == null) return null;
        return modelName.replaceAll("-\\d{4}-\\d{2}-\\d{2}$", "");
    }
}
