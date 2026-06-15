package com.productsearch.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.productsearch.infra.LLMCallResult;
import com.productsearch.model.ProductSearchResponse;
import com.productsearch.model.RerankResult;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ProductRerankerService {

    private static final int MAX_CANDIDATES_TO_RANK = 10;
    private static final String SYSTEM_PROMPT =
            "You are a product recommendation expert. Rank the candidate products from most to least " +
            "relevant for the user's request. Consider category fit, feature match, brand reputation, " +
            "and price-to-value. Call the tool with the ranked product IDs.";
    private static final ToolSpecification RERANK_TOOL = ToolSpecification.builder()
            .name("emit_ranked_ids")
            .description("Emit product IDs ranked from most to least relevant.")
            .parameters(JsonObjectSchema.builder()
                    .addProperty("ranked_ids", JsonArraySchema.builder()
                            .description("Product IDs ordered from best match to worst. No duplicates.")
                            .items(new JsonStringSchema()).build())
                    .required("ranked_ids").build())
            .build();

    private final OpenAiChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProductRerankerService(@Qualifier("rerankerModel") OpenAiChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public RerankResult rerankWithUsage(String userQuery, List<ProductSearchResponse.Product> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) return RerankResult.empty();
        if (candidates.size() <= topN) return RerankResult.noRerank(candidates.size(), candidates);
        try {
            List<ProductSearchResponse.Product> pool = candidates.size() > MAX_CANDIDATES_TO_RANK
                    ? candidates.subList(0, MAX_CANDIDATES_TO_RANK) : candidates;
            long start = System.currentTimeMillis();
            ChatResponse resp = chatModel.chat(ChatRequest.builder()
                    .messages(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(buildUserMessage(userQuery, pool, topN)))
                    .toolSpecifications(RERANK_TOOL).toolChoice(ToolChoice.REQUIRED).maxOutputTokens(64).build());

            String args = resp.aiMessage().toolExecutionRequests().get(0).arguments();
            JsonNode arr = objectMapper.readTree(args).get("ranked_ids");
            List<String> ids = new ArrayList<>();
            if (arr != null && arr.isArray()) arr.forEach(n -> ids.add(n.asText()));

            LLMCallResult<String> r = LLMCallResult.fromChat(resp, start);
            List<ProductSearchResponse.Product> reranked = orderByIds(pool, ids, topN);
            log.info("Reranker — pool={}/{} topN={} → {} (tokens in/out={}/{}, {} ms)",
                    pool.size(), candidates.size(), topN, reranked.size(), r.inputTokens(), r.outputTokens(), r.durationMs());
            return new RerankResult(reranked, r.modelName(), r.inputTokens(), r.outputTokens(), r.durationMs(), candidates.size(), reranked.size());
        } catch (Exception e) {
            log.warn("LLM reranking failed ({}), falling back to Pinecone order", e.getMessage());
            return RerankResult.noRerank(candidates.size(), candidates.subList(0, Math.min(topN, candidates.size())));
        }
    }

    private String buildUserMessage(String userQuery, List<ProductSearchResponse.Product> candidates, int topN) {
        StringBuilder sb = new StringBuilder("User is looking for: \"").append(userQuery).append("\"\n\nCandidate products:\n");
        for (ProductSearchResponse.Product p : candidates) {
            List<String> extras = new ArrayList<>();
            if (p.isFreeShipping())     extras.add("FreeShipping");
            if (p.isWarrantyIncluded()) extras.add("Warranty");
            sb.append(String.format("ID=%s | %s | %s | %s | $%.0f | %s%n", p.getProductId(),
                    p.getName() != null ? p.getName() : "(unnamed)", p.getBrandName() != null ? p.getBrandName() : "?",
                    p.getCategory() != null ? p.getCategory() : "?", p.getPrice(),
                    extras.isEmpty() ? "no extras" : String.join("+", extras)));
        }
        return sb.append("\nCall the tool with the top ").append(topN).append(" product IDs most relevant to the user's request.").toString();
    }

    private List<ProductSearchResponse.Product> orderByIds(List<ProductSearchResponse.Product> candidates, List<String> rankedIds, int topN) {
        Map<String, ProductSearchResponse.Product> byId = candidates.stream()
                .collect(Collectors.toMap(ProductSearchResponse.Product::getProductId, p -> p, (a, b) -> a));
        List<ProductSearchResponse.Product> result = new ArrayList<>();
        for (String id : rankedIds) { ProductSearchResponse.Product p = byId.get(id); if (p != null && result.size() < topN) result.add(p); }
        if (result.size() < topN) {
            Set<String> used = result.stream().map(ProductSearchResponse.Product::getProductId).collect(Collectors.toSet());
            for (ProductSearchResponse.Product p : candidates) { if (!used.contains(p.getProductId()) && result.size() < topN) result.add(p); }
        }
        return result;
    }
}
