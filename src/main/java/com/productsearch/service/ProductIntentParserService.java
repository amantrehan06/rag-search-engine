package com.productsearch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.productsearch.constants.ProductSearchConstants;
import com.productsearch.infra.CatalogMetadataProvider;
import com.productsearch.infra.LLMCallResult;
import com.productsearch.model.IntentParserResult;
import com.productsearch.model.ProductSearchIntent;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ProductIntentParserService  {

    private static final String TOOL_NAME = "parse_product_intent";
    private static final String TOOL_DESC =
            "Extract a structured product-search intent (category, brand, price, features) from the user's query.";

    private final OpenAiChatModel chatModel;
    private final CatalogMetadataProvider catalogMetadata;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProductIntentParserService(@Qualifier("intentModel") OpenAiChatModel chatModel,
                                          CatalogMetadataProvider catalogMetadata) {
        this.chatModel = chatModel;
        this.catalogMetadata = catalogMetadata;
    }

    public IntentParserResult parseProductIntentWithRawJson(String userQuery) {
        try {
            CatalogMetadataProvider.Snapshot snap = catalogMetadata.get();
            String categoryList = snap.categoryIds().isEmpty() ? "<no catalog loaded>"
                    : snap.categoryIds().stream().map(c -> "'" + c + "'").collect(Collectors.joining(", "));
            String brandList = snap.brandCodes().isEmpty() ? "<no catalog loaded>"
                    : String.join(", ", snap.brandCodes());

            ToolSpecification tool = ToolSpecification.builder()
                    .name(TOOL_NAME)
                    .description(TOOL_DESC)
                    .parameters(buildSchema(categoryList, brandList))
                    .build();

            long start = System.currentTimeMillis();
            ChatResponse resp = chatModel.chat(ChatRequest.builder()
                    .messages(SystemMessage.from(systemPrompt(categoryList, brandList)),
                              UserMessage.from(userQuery))
                    .toolSpecifications(tool)
                    .toolChoice(ToolChoice.REQUIRED)
                    .build());

            List<ToolExecutionRequest> calls = resp.aiMessage().toolExecutionRequests();
            if (calls == null || calls.isEmpty()) {
                throw new RuntimeException("LLM returned no tool call for intent parsing");
            }
            String argumentsJson = calls.get(0).arguments();
            ProductSearchIntent intent = objectMapper.readValue(argumentsJson, ProductSearchIntent.class);

            intent.setOriginalQuery(userQuery);
            if (intent.getConfidence() == null) {
                intent.setConfidence(ProductSearchConstants.DEFAULT_CONFIDENCE);
            }
            validateIntent(intent);

            LLMCallResult<String> r = LLMCallResult.fromChat(resp, start);
            log.info("Intent parsed — model={} in/out tokens={}/{} {} ms",
                    r.modelName(), r.inputTokens(), r.outputTokens(), r.durationMs());

            return new IntentParserResult(intent, argumentsJson,
                    r.modelName(), r.inputTokens(), r.outputTokens(), r.durationMs());

        } catch (Exception e) {
            log.error("Intent parsing failed: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to parse product intent: " + e.getMessage(), e);
        }
    }

    private static String systemPrompt(String categoryList, String brandList) {
        return "Parse the user's natural language product-search query into a structured intent. " +
                "CATEGORY ROUTING: " +
                "1) categoryId — set ONLY when the user names a category explicitly. " +
                "   Valid values: " + categoryList + ". " +
                "   Pick the category whose name most closely matches the user's wording. " +
                "2) categorySearchQuery — set ONLY when the user describes a vibe / use case " +
                "   without naming a category. " +
                "   Write 1-2 vivid sentences describing the ideal product category. " +
                "3) NEVER set both. If categoryId is clear, leave categorySearchQuery null. " +
                "BRAND: " +
                "Set brandCodes to the list of brand codes the user explicitly named. " +
                "Valid values (pick any subset, in user-mention order): " + brandList + ". " +
                "Examples: 'Sony or Bose headphones' → ['SONY','BOSE']. 'Nike shoes' → ['NIKE']. " +
                "Leave null or empty when no catalog brand is named. " +
                "FEATURES (hard filters — true ONLY when explicitly required, never default to false): " +
                "freeShippingRequired, warrantyRequired. " +
                "PRICE: " +
                "Parse maxPrice/minPrice from 'under $400', 'over $200', 'between $100-$300'. " +
                "productSearchQuery: " +
                "Always write a 1-2 sentence rich description of the ideal product covering use case, " +
                "feel, and any quality cues ('budget', 'premium', 'flagship') — this is the embedded vector " +
                "search text. Leave null only if there is truly no context about product style or use case.";
    }

    private static JsonObjectSchema buildSchema(String categoryList, String brandList) {
        return JsonObjectSchema.builder()
                .addProperty("categoryId", JsonStringSchema.builder()
                        .description("Category id ONLY when the user names a category explicitly. " +
                                "Valid values: " + categoryList + ". LEAVE NULL when the user expresses a vibe " +
                                "or use case without naming a category (use categorySearchQuery in that case).")
                        .build())
                .addProperty("brandCodes", JsonArraySchema.builder()
                        .description("List of brand codes the user explicitly named — OR-combined. " +
                                "Valid values (any subset): " + brandList + ". " +
                                "Leave NULL or empty when no catalog brand is named.")
                        .items(new JsonStringSchema())
                        .build())
                .addProperty("categorySearchQuery", JsonStringSchema.builder()
                        .description("Set ONLY when categoryId is null. Write 1-2 vivid sentences describing the " +
                                "ideal product category for embedding-based category lookup.")
                        .build())
                .addProperty("productSearchQuery", JsonStringSchema.builder()
                        .description("Write 1-2 vivid sentences describing the ideal product, drawing on quality " +
                                "cues, intended use, and lifestyle. Embedded for the product vector search.")
                        .build())
                .addProperty("freeShippingRequired", JsonBooleanSchema.builder()
                        .description("Set true ONLY when user explicitly requires free shipping. Leave NULL otherwise.")
                        .build())
                .addProperty("warrantyRequired", JsonBooleanSchema.builder()
                        .description("Set true ONLY when user explicitly requires warranty. Leave NULL otherwise.")
                        .build())
                .addProperty("maxPrice", JsonNumberSchema.builder()
                        .description("MAXIMUM price in dollars (e.g. 'under $400' → 400). Leave NULL when not stated.")
                        .build())
                .addProperty("minPrice", JsonNumberSchema.builder()
                        .description("MINIMUM price in dollars (e.g. 'over $500' → 500). Leave NULL when not stated.")
                        .build())
                .addProperty("confidence", JsonNumberSchema.builder()
                        .description("Confidence in this parsing (0.0 to 1.0). 1.0 = very clear, 0.5 = ambiguous.")
                        .build())
                .build();
    }

    private void validateIntent(ProductSearchIntent intent) {
        boolean hasCategory = (intent.getCategoryId() != null && !intent.getCategoryId().isBlank())
                || (intent.getCategorySearchQuery() != null && !intent.getCategorySearchQuery().isBlank());
        List<String> missing = new ArrayList<>();
        if (!hasCategory) missing.add("category");
        intent.setMissingFields(missing);
    }

    public List<String> checkMandatoryFieldsAndGenerateQuestions(ProductSearchIntent intent) {
        List<String> questions = new ArrayList<>();
        boolean hasCategory = (intent.getCategoryId() != null && !intent.getCategoryId().isBlank())
                || (intent.getCategorySearchQuery() != null && !intent.getCategorySearchQuery().isBlank());
        if (!hasCategory) {
            CatalogMetadataProvider.Snapshot snap = catalogMetadata.get();
            String cats = snap.categoryIds().isEmpty() ? "audio, footwear, furniture"
                    : String.join(", ", snap.categoryIds());
            questions.add("What kind of product are you looking for? (e.g. " + cats + ", or describe the use case)");
        }
        return questions;
    }

}
