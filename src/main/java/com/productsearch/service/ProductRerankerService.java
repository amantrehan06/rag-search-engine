package com.productsearch.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.productsearch.infra.LLMCallResult;
import com.productsearch.model.ProductSearchResponse;
import com.productsearch.model.RerankResult;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
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

    private final OpenAiChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProductRerankerService(@Qualifier("rerankerModel") OpenAiChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public RerankResult rerankWithUsage(String userQuery,
                                        List<ProductSearchResponse.Product> candidates,
                                        int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return RerankResult.empty();
        }
        if (candidates.size() <= topN) {
            return RerankResult.noRerank(candidates.size(), candidates);
        }

        try {
            List<ProductSearchResponse.Product> pool = candidates.size() > MAX_CANDIDATES_TO_RANK
                    ? candidates.subList(0, MAX_CANDIDATES_TO_RANK)
                    : candidates;

            long start = System.currentTimeMillis();
            ChatResponse resp = chatModel.chat(ChatRequest.builder()
                    .messages(SystemMessage.from(buildSystemPrompt(topN)),
                              UserMessage.from(buildUserMessage(userQuery, pool, topN)))
                    .maxOutputTokens(64)
                    .build());
            LLMCallResult<String> r = LLMCallResult.fromChat(resp, start);

            List<ProductSearchResponse.Product> reranked = orderByIds(pool, parseIds(r.result()), topN);
            log.info("Reranker — pool={}/{} topN={} → {} (tokens in/out={}/{}, {} ms)",
                    pool.size(), candidates.size(), topN, reranked.size(),
                    r.inputTokens(), r.outputTokens(), r.durationMs());

            return new RerankResult(reranked, r.modelName(),
                    r.inputTokens(), r.outputTokens(), r.durationMs(),
                    candidates.size(), reranked.size());

        } catch (Exception e) {
            log.warn("LLM reranking failed ({}), falling back to Pinecone order", e.getMessage());
            List<ProductSearchResponse.Product> fallback = candidates.subList(0, Math.min(topN, candidates.size()));
            return RerankResult.noRerank(candidates.size(), fallback);
        }
    }

    private String buildSystemPrompt(int topN) {
        return "You are a product recommendation expert. " +
               "Given the user's request and a list of candidate products, rank them from most to least relevant. " +
               "Consider category fit, feature match, brand reputation, and price-to-value. " +
               "Return ONLY a JSON array of exactly " + topN + " product IDs (strings) from best match to worst — " +
               "no explanation.";
    }

    private String buildUserMessage(String userQuery, List<ProductSearchResponse.Product> candidates, int topN) {
        StringBuilder sb = new StringBuilder("User is looking for: \"").append(userQuery).append("\"\n\nCandidate products:\n");
        for (ProductSearchResponse.Product p : candidates) {
            sb.append(String.format("ID=%s | %s | %s | %s | $%.0f | %s%n",
                    p.getProductId(),
                    p.getName() != null ? p.getName() : "(unnamed)",
                    p.getBrandName() != null ? p.getBrandName() : "?",
                    p.getCategory() != null ? p.getCategory() : "?",
                    p.getPrice(),
                    formatFeatures(p)));
        }
        sb.append("\nReturn the top ").append(topN).append(" product IDs most relevant to the user's request.");
        return sb.toString();
    }

    private String formatFeatures(ProductSearchResponse.Product p) {
        List<String> f = new ArrayList<>();
        if (p.isFreeShipping())     f.add("FreeShipping");
        if (p.isWarrantyIncluded()) f.add("Warranty");
        return f.isEmpty() ? "no extras" : String.join("+", f);
    }

    private List<String> parseIds(String llmResponse) throws Exception {
        int start = llmResponse.indexOf('[');
        int end = llmResponse.lastIndexOf(']');
        if (start < 0 || end <= start) return List.of();
        return objectMapper.readValue(llmResponse.substring(start, end + 1), new TypeReference<>() {});
    }

    private List<ProductSearchResponse.Product> orderByIds(List<ProductSearchResponse.Product> candidates,
                                                            List<String> rankedIds, int topN) {
        Map<String, ProductSearchResponse.Product> byId = candidates.stream()
                .collect(Collectors.toMap(ProductSearchResponse.Product::getProductId, p -> p, (a, b) -> a));

        List<ProductSearchResponse.Product> result = new ArrayList<>();
        for (String id : rankedIds) {
            ProductSearchResponse.Product p = byId.get(id);
            if (p != null && result.size() < topN) result.add(p);
        }
        if (result.size() < topN) {
            Set<String> used = result.stream().map(ProductSearchResponse.Product::getProductId).collect(Collectors.toSet());
            for (ProductSearchResponse.Product p : candidates) {
                if (!used.contains(p.getProductId()) && result.size() < topN) result.add(p);
            }
        }
        return result;
    }
}
