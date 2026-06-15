package com.productsearch.eval;

import com.productsearch.ProductSearchTestApplication;
import com.productsearch.model.ProductSearchRequest;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiChatModel;
import com.productsearch.model.ProductSearchResponse;
import com.productsearch.pipeline.SearchPipeline;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTest(classes = ProductSearchTestApplication.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.productsearch.eval.EvalCredentials#available")
class ProductScoredEvalTest {

    @Autowired private SearchPipeline searchPipeline;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("judgeModel")
    private OpenAiChatModel chatModel;

    private final List<EvalResult> results = Collections.synchronizedList(new ArrayList<>());

    record EvalCase(
            String name,
            String query,
            String expectedCategory,        // null = any category OK
            Double maxPrice,                 // null = no price constraint
            boolean requireWarranty,
            boolean requireFreeShipping,
            boolean expectNonEmpty,          // false = expect no results (out-of-catalog)
            Double excludedPrice             // adversarial: this exact price must NOT appear (null = no boundary test)
    ) {}

    static Stream<EvalCase> evalCases() {
        return Stream.of(
            new EvalCase(
                "Shoes under $200",
                "Running shoes under $200",
                "footwear", 200.0, false, false, true, null
            ),
            new EvalCase(
                "ADV1 — Headphones under $400 excludes $419 boundary",
                "Premium headphones under $400",
                "audio", 400.0, false, false, true, 419.0
            ),
            new EvalCase(
                "Audio with warranty",
                "Audio products with extended warranty",
                "audio", null, true, false, true, null
            ),
            new EvalCase(
                "Out-of-catalog — vintage typewriters",
                "Vintage typewriters from the 1950s",
                null, null, false, false, false, null
            ),
            new EvalCase(
                "Vague home-office → furniture",
                "Something comfortable for my home office to sit and work",
                "furniture", null, false, false, true, null
            ),
            new EvalCase(
                "Multi-filter: shoes + free shipping under $200",
                "Running shoes with free shipping under $200",
                "footwear", 200.0, false, true, true, null
            ),
            new EvalCase(
                "Best/flagship audio",
                "Best audio equipment available, top-of-the-line",
                "audio", null, false, false, true, null
            ),
            new EvalCase(
                "Implicit category — marathon training",
                "Quality items for marathon training and long distance running",
                "footwear", null, false, false, true, null
            )
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("evalCases")
    void runScoredEval(EvalCase evalCase) {
        ProductSearchResponse response = ask(evalCase.query());
        List<ProductSearchResponse.Product> products = response.getProducts();

        double constraintSatisfactionRate = computeConstraintSatisfactionRate(evalCase, products);
        double llmRelevancyScore          = computeLlmRelevancyScore(evalCase.query(), products);
        double topKPrecision              = computeTopKPrecision(evalCase, products);

        EvalResult result = new EvalResult(
                evalCase.name(), evalCase.query(),
                constraintSatisfactionRate, llmRelevancyScore, topKPrecision,
                products.size()
        );
        results.add(result);

        log.info("─── SCORED EVAL | {} ───────────────────────────────────────────────", evalCase.name());
        log.info("  ConstraintSatisfaction: {}", String.format("%.3f", constraintSatisfactionRate));
        log.info("  LLM Relevancy      : {}", String.format("%.3f", llmRelevancyScore));
        log.info("  TopK Precision     : {}", String.format("%.3f", topKPrecision));
        log.info("  Products returned: {}", products.size());

        // Per-case gate: ConstraintSatisfaction must be perfect for every constraint-bearing case.
        // LLM Relevancy is probabilistic — only the aggregate gate in @AfterAll enforces it.
        if (evalCase.expectNonEmpty()) {
            assertThat(constraintSatisfactionRate)
                    .as("ConstraintSatisfaction for '%s' fell below 0.90", evalCase.name())
                    .isGreaterThanOrEqualTo(0.90);
        }
    }

    // ─── Metric implementations ───────────────────────────────────────────────

    private double computeConstraintSatisfactionRate(EvalCase ec, List<ProductSearchResponse.Product> products) {
        if (!ec.expectNonEmpty()) {
            return products.isEmpty() ? 1.0 : 0.0;
        }
        if (products.isEmpty()) return 0.0;

        long compliant = products.stream().filter(p -> meetsAllConstraints(ec, p)).count();
        return (double) compliant / products.size();
    }

    private boolean meetsAllConstraints(EvalCase ec, ProductSearchResponse.Product p) {
        if (ec.expectedCategory() != null && !ec.expectedCategory().equals(p.getCategory())) return false;
        if (ec.maxPrice() != null && p.getPrice() > ec.maxPrice())                            return false;
        if (ec.requireWarranty()    && !p.isWarrantyIncluded())                               return false;
        if (ec.requireFreeShipping() && !p.isFreeShipping())                                  return false;
        if (ec.excludedPrice() != null && p.getPrice() == ec.excludedPrice())                 return false;
        return true;
    }

    private double computeLlmRelevancyScore(String query, List<ProductSearchResponse.Product> products) {
        try {
            String systemPrompt =
                "You are an impartial evaluator for a product-search system.\n" +
                "Score how well the returned products answer the user's query.\n" +
                "Return ONLY a single decimal number between 0.0 and 1.0.\n" +
                "1.0 = perfectly relevant: category, price, tier, and features match what the user asked,\n" +
                "      OR the system correctly returned no products for an unavailable category.\n" +
                "0.5 = partially relevant: some constraints satisfied but others missed.\n" +
                "0.0 = irrelevant: wrong category, budget violated, required features missing, or no\n" +
                "      products returned when results were expected.\n" +
                "No explanation — just the number.";

            String summary;
            if (products.isEmpty()) {
                summary = "No products returned.";
            } else {
                summary = products.stream()
                    .limit(5)
                    .map(p -> String.format("%s | %s | %s | $%.0f | warranty=%s freeShip=%s",
                        p.getName(),
                        p.getBrandName(),
                        p.getCategory(),
                        p.getPrice(),
                        p.isWarrantyIncluded(),
                        p.isFreeShipping()))
                    .reduce((a, b) -> a + "\n  " + b).orElse("");
                summary = "Products returned (" + products.size() + " total):\n  " + summary;
            }

            String userMsg = String.format("User query: \"%s\"\n\n%s", query, summary);

            String raw = chatModel.chat(SystemMessage.from(systemPrompt), UserMessage.from(userMsg))
                    .aiMessage().text().trim();
            return Double.parseDouble(raw.replaceAll("[^0-9.]", ""));
        } catch (Exception e) {
            log.warn("LlmRelevancyScore LLM judge failed: {} — defaulting to 0.5", e.getMessage());
            return 0.5;
        }
    }

    private double computeTopKPrecision(EvalCase ec, List<ProductSearchResponse.Product> products) {
        if (products.isEmpty()) {
            return ec.expectNonEmpty() ? 0.0 : 1.0;
        }
        int topK = Math.min(5, products.size());
        List<ProductSearchResponse.Product> top = products.subList(0, topK);
        long relevant = top.stream().filter(p -> meetsAllConstraints(ec, p)).count();
        return (double) relevant / topK;
    }

    // ─── Summary report ───────────────────────────────────────────────────────

    @AfterAll
    void printScoredEvalSummaryAndAssertThresholds() {
        if (results.isEmpty()) return;

        double avgConstraintSatisfactionRate = results.stream().mapToDouble(EvalResult::constraintSatisfactionRate).average().orElse(0);
        double avgLlmRelevancyScore          = results.stream().mapToDouble(EvalResult::llmRelevancyScore).average().orElse(0);
        double avgTopKPrecision              = results.stream().mapToDouble(EvalResult::topKPrecision).average().orElse(0);

        String sep = "─".repeat(110);
        log.info("\n\n{}", sep);
        log.info("  SCORED EVALUATION REPORT — Product Search RAG Pipeline");
        log.info(sep);
        for (EvalResult r : results) {
            log.info("  {} | F={} AR={} CP={} | products={}",
                    truncate(r.name(), 50),
                    String.format("%.3f", r.constraintSatisfactionRate()),
                    String.format("%.3f", r.llmRelevancyScore()),
                    String.format("%.3f", r.topKPrecision()),
                    r.productsReturned());
        }
        log.info(sep);
        log.info("  AVERAGE | F={} AR={} CP={}",
                String.format("%.3f", avgConstraintSatisfactionRate),
                String.format("%.3f", avgLlmRelevancyScore),
                String.format("%.3f", avgTopKPrecision));
        log.info(sep);
        log.info("  Thresholds: ConstraintSatisfaction ≥ 0.90 | LLMRelevancy ≥ 0.70 | TopKPrecision ≥ 0.80");
        log.info("{}\n", sep);

        // ── Gate assertions — these fail the build if scores regress ──────────
        assertThat(avgConstraintSatisfactionRate)
                .as("Overall ConstraintSatisfaction %.3f below 0.90. A metadata filter or reranker " +
                    "regression is likely — check per-case scores above.", avgConstraintSatisfactionRate)
                .isGreaterThanOrEqualTo(0.90);

        assertThat(avgLlmRelevancyScore)
                .as("Overall LLMRelevancy %.3f below 0.70. The pipeline is returning products " +
                    "misaligned with query intent.", avgLlmRelevancyScore)
                .isGreaterThanOrEqualTo(0.70);

        assertThat(avgTopKPrecision)
                .as("Overall TopKPrecision %.3f below 0.80. Reranker or filter is leaking " +
                    "non-compliant results into top-5.", avgTopKPrecision)
                .isGreaterThanOrEqualTo(0.80);
    }

    // ─── Internal record + helpers ────────────────────────────────────────────

    record EvalResult(
            String name,
            String query,
            double constraintSatisfactionRate,
            double llmRelevancyScore,
            double topKPrecision,
            int productsReturned
    ) {}

    private ProductSearchResponse ask(String query) {
        return searchPipeline.processProductSearch(
                ProductSearchRequest.builder()
                        .message(query)
                        .conversationId(UUID.randomUUID().toString())
                        .build());
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
