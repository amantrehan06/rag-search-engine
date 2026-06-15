package com.productsearch.pipeline;

import com.productsearch.constants.ProductSearchConstants;
import com.productsearch.model.IntentParserResult;
import com.productsearch.model.ProductSearchIntent;
import com.productsearch.model.ProductSearchResponse;
import com.productsearch.model.ProductSearchSteps;
import com.productsearch.service.ProductIntentParserService;
import com.productsearch.tracing.ProductPipelineTracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class IntentParseStage {

    private final ProductIntentParserService parser;
    private final ProductPipelineTracer tracer;
    private final Map<String, ProductSearchIntent> accumulator = new ConcurrentHashMap<>();

    public void run(SearchContext ctx) {
        IntentParserResult result = tracer.traceIntentParse(() -> parser.parseProductIntentWithRawJson(ctx.combinedQuery));

        ctx.intent = result.intent();
        ctx.llmJsonResponse = result.rawJson();
        ctx.intentConfidence = ctx.intent.getConfidence();

        ProductSearchIntent prior = accumulator.get(ctx.sessionId);
        if (prior != null) merge(prior, ctx.intent);
        accumulator.put(ctx.sessionId, ctx.intent);

        Double c = ctx.intent.getConfidence();
        ctx.stepsBuilder.intentParser(ProductSearchSteps.IntentParserStep.builder()
                .originalQuery(ctx.request.getMessage())
                .llmResponse(ctx.llmJsonResponse)
                .confidence(String.valueOf(c != null ? c : ProductSearchConstants.DEFAULT_CONFIDENCE))
                .missingFields(ctx.intent.getMissingFields())
                .timestamp(LocalDateTime.now().toString())
                .build());

        List<String> followUps = parser.checkMandatoryFieldsAndGenerateQuestions(ctx.intent);
        if (followUps.isEmpty()) return;

        StringBuilder body = new StringBuilder("I need a bit more information to help you find the perfect products. ");
        if (followUps.size() == 1) {
            body.append(followUps.get(0));
        } else {
            body.append("Here are a few questions:\n");
            for (int i = 0; i < followUps.size(); i++) {
                body.append(i + 1).append(". ").append(followUps.get(i)).append("\n");
            }
        }
        ctx.response = ProductSearchResponse.builder()
                .success(true)
                .message(ProductSearchConstants.FOLLOW_UP_QUESTIONS_MESSAGE)
                .response(body.toString())
                .conversationId(ctx.sessionId)
                .products(new ArrayList<>())
                .steps(ctx.stepsBuilder.build())
                .build();
    }

    public void clear(String sessionId) {
        accumulator.remove(sessionId);
    }

    private static void merge(ProductSearchIntent prior, ProductSearchIntent fresh) {
        if (fresh.getCategoryId() == null) fresh.setCategoryId(prior.getCategoryId());
        if (fresh.getBrandCodes() == null || fresh.getBrandCodes().isEmpty()) fresh.setBrandCodes(prior.getBrandCodes());
        if (fresh.getCategorySearchQuery() == null) fresh.setCategorySearchQuery(prior.getCategorySearchQuery());
        if (fresh.getProductSearchQuery() == null) fresh.setProductSearchQuery(prior.getProductSearchQuery());
        if (fresh.getFreeShippingRequired() == null) fresh.setFreeShippingRequired(prior.getFreeShippingRequired());
        if (fresh.getWarrantyRequired() == null) fresh.setWarrantyRequired(prior.getWarrantyRequired());
        if (fresh.getMaxPrice() == null) fresh.setMaxPrice(prior.getMaxPrice());
        if (fresh.getMinPrice() == null) fresh.setMinPrice(prior.getMinPrice());
    }
}
