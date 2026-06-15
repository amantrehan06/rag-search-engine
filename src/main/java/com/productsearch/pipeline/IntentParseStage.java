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

    public IntentResult run(String combinedQuery, String sessionId, String originalMessage) {
        IntentParserResult result = tracer.traceIntentParse(() -> parser.parseProductIntentWithRawJson(combinedQuery));

        ProductSearchIntent intent = result.intent();
        String rawJson = result.rawJson();
        double confidence = intent.getConfidence() != null
                ? intent.getConfidence()
                : ProductSearchConstants.DEFAULT_CONFIDENCE;

        ProductSearchIntent prior = accumulator.get(sessionId);
        if (prior != null) merge(prior, intent);
        accumulator.put(sessionId, intent);

        ProductSearchSteps.IntentParserStep step = ProductSearchSteps.IntentParserStep.builder()
                .originalQuery(originalMessage)
                .llmResponse(rawJson)
                .confidence(String.valueOf(confidence))
                .missingFields(intent.getMissingFields())
                .timestamp(LocalDateTime.now().toString())
                .build();

        List<String> followUps = parser.checkMandatoryFieldsAndGenerateQuestions(intent);
        if (!followUps.isEmpty()) {
            StringBuilder body = new StringBuilder("I need a bit more information to help you find the perfect products. ");
            if (followUps.size() == 1) {
                body.append(followUps.get(0));
            } else {
                body.append("Here are a few questions:\n");
                for (int i = 0; i < followUps.size(); i++) {
                    body.append(i + 1).append(". ").append(followUps.get(i)).append("\n");
                }
            }
            ProductSearchResponse followUpResponse = ProductSearchResponse.builder()
                    .success(true)
                    .message(ProductSearchConstants.FOLLOW_UP_QUESTIONS_MESSAGE)
                    .response(body.toString())
                    .conversationId(sessionId)
                    .products(new ArrayList<>())
                    .steps(ProductSearchSteps.builder().intentParser(step).build())
                    .build();
            return new IntentResult(intent, rawJson, confidence, step, followUpResponse);
        }

        return new IntentResult(intent, rawJson, confidence, step, null);
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
