package com.productsearch.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.productsearch.constants.ProductSearchConstants;
import com.productsearch.evaluation.ProductJudgeService;
import com.productsearch.infra.SessionManager;
import com.productsearch.model.ProductSearchResponse;
import com.productsearch.model.ProductSearchSteps;
import com.productsearch.tracing.ProductPipelineTracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RespondStage {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SessionManager sessionManager;
    private final IntentParseStage intentParseStage;
    private final ProductJudgeService judge;
    private final ProductPipelineTracer tracer;

    public void run(SearchContext ctx) {
        ProductSearchSteps steps = ctx.stepsBuilder.build();

        if (!ctx.rankedResults.isEmpty()) {
            sessionManager.clearSession(ctx.sessionId);
            intentParseStage.clear(ctx.sessionId);

            String traceId = ctx.rootSpan.getSpanContext().getTraceId();
            tracer.finishRootSpan(ctx.rootSpan, ctx.rootScope,
                    "Found " + ctx.rankedResults.size() + " product(s) in " + ctx.rankedResults.get(0).getCategory());
            ctx.rootSpan = null;

            judge.evaluateAndSubmit(traceId, ctx.request.getMessage(), ctx.intent, ctx.rankedResults);

            ctx.response = ProductSearchResponse.builder()
                    .success(true)
                    .message(ProductSearchConstants.PRODUCT_SEARCH_SUCCESS_MESSAGE)
                    .response(foundText(ctx.rankedResults, steps))
                    .conversationId(ctx.sessionId)
                    .products(ctx.rankedResults)
                    .steps(steps)
                    .build();
            return;
        }

        tracer.finishRootSpan(ctx.rootSpan, ctx.rootScope, "No products found");
        ctx.rootSpan = null;
        ctx.response = ProductSearchResponse.builder()
                .success(true)
                .message(ProductSearchConstants.NO_PRODUCTS_MESSAGE)
                .response(emptyText(steps))
                .conversationId(ctx.sessionId)
                .products(new ArrayList<>())
                .steps(steps)
                .build();
    }

    private static String foundText(List<ProductSearchResponse.Product> products, ProductSearchSteps steps) {
        StringBuilder r = new StringBuilder("**Search Process Summary**\n\n");
        appendIntent(steps, r);
        appendCategory(steps, r, false);
        appendProductSearch(steps, r, false);
        r.append("---\n\n")
         .append("**Search Complete.** I found ").append(products.size()).append(" products matching your criteria.\n\n")
         .append("**Next Steps:**\n")
         .append("- Would you like to refine your search?\n")
         .append("- Need more details about any specific product?\n")
         .append("- Want to see more options or a different tier?\n\n")
         .append("Let me know how I can help you further.");
        return r.toString();
    }

    private static String emptyText(ProductSearchSteps steps) {
        StringBuilder r = new StringBuilder("**Search Process Summary**\n\n");
        appendIntent(steps, r);
        appendCategory(steps, r, true);
        appendProductSearch(steps, r, true);
        r.append("---\n\n")
         .append("**No Products Found**\n\n")
         .append("I couldn't find any products matching your criteria. This could be due to:\n")
         .append("- Specific requirements that are too restrictive\n")
         .append("- Out-of-stock or unavailable variants\n\n")
         .append("**Suggestions:**\n")
         .append("- Be more flexible with category preferences\n")
         .append("- Adjust your budget\n")
         .append("- Consider alternative brands\n\n")
         .append("Would you like to refine your search?");
        return r.toString();
    }

    private static void appendIntent(ProductSearchSteps steps, StringBuilder r) {
        ProductSearchSteps.IntentParserStep s = steps.getIntentParser();
        if (s == null) return;
        r.append("**1. Intent Analysis**\nLLM Response JSON:\n```json\n")
         .append(s.getLlmResponse()).append("\n```\n");
        if (s.getMissingFields() != null && !s.getMissingFields().isEmpty()) {
            r.append("- **Missing details:** ").append(String.join(", ", s.getMissingFields())).append("\n");
        }
        r.append("\n");
    }

    private static void appendCategory(ProductSearchSteps steps, StringBuilder r, boolean emptyTone) {
        ProductSearchSteps.CategorySearchStep s = steps.getCategorySearch();
        if (s == null) return;
        r.append("**2. Category Discovery**\n");
        if (ProductSearchConstants.DIRECT_CATEGORY_SPECIFICATION_METHOD.equals(s.getSearchMethod())) {
            r.append("You specified a category directly: ").append(s.getCategoryId()).append("\n");
        } else {
            r.append(emptyTone ? "I searched for categories matching: _\"" : "Based on your preference: _\"")
             .append(s.getEmbeddingQuery()).append("\"_\n");
            if (s.getTopResults() != null && !s.getTopResults().isEmpty()) {
                r.append(emptyTone ? "- **Found categories:** " : "- Best-matching categories: ")
                 .append(String.join(", ", s.getTopResults())).append("\n");
            } else {
                r.append(emptyTone ? "- **No matching categories found**\n" : "- No matching categories found for this preference\n");
            }
        }
        r.append("\n");
    }

    private static void appendProductSearch(ProductSearchSteps steps, StringBuilder r, boolean emptyTone) {
        ProductSearchSteps.ProductSearchStep s = steps.getProductSearch();
        if (s == null) return;
        r.append("**3. Product Search**\n");
        if (ProductSearchConstants.NO_SEARCH_QUERY.equals(s.getEmbeddingSearchQuery())) {
            r.append("Product search was not performed because no category was resolved.\n\n");
            return;
        }
        if (ProductSearchConstants.NO_SEARCH_METHOD.equals(s.getSearchMethod())) {
            r.append("Product search was not performed due to missing category information.\n\n");
            return;
        }
        r.append(emptyTone ? "I attempted a hybrid search:\n" : "I used a hybrid search approach:\n")
         .append("- **Vector Search:** \"").append(s.getEmbeddingSearchQuery()).append("\"\n");
        if (s.getMetadataFilter() != null && !s.getMetadataFilter().isEmpty()) {
            r.append("- **Metadata Filter Query:**\n```json\n");
            try { r.append(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(s.getMetadataFilter())); }
            catch (Exception e) { r.append(s.getMetadataFilter().toString()); }
            r.append("\n```\n");
        } else {
            r.append("- **Metadata Filter:** No filters applied\n");
        }
        r.append(emptyTone
                ? "- **Results:** No products matched your criteria\n\n"
                : "- **Results:** Found " + s.getResultsCount() + " matching products\n\n");
    }
}
