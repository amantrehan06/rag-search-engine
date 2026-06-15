package com.productsearch.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.productsearch.infra.LLMCallResult;
import com.productsearch.model.ProductSearchIntent;
import com.productsearch.model.ProductSearchResponse;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class ProductJudgeService {

    private final OpenAiChatModel chatModel;
    private final LangSmithFeedbackService feedbackService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProductJudgeService(@Qualifier("judgeModel") OpenAiChatModel chatModel,
                               LangSmithFeedbackService feedbackService) {
        this.chatModel = chatModel;
        this.feedbackService = feedbackService;
    }

    @Async("judgeExecutor")
    public void evaluateAndSubmit(String traceId,
                                  String userQuery,
                                  ProductSearchIntent intent,
                                  List<ProductSearchResponse.Product> products) {
        try {
            // Give the BatchSpanProcessor + LangSmith ingestion a moment to materialise the run.
            Thread.sleep(2_000);

            long start = System.currentTimeMillis();
            ChatResponse resp = chatModel.chat(ChatRequest.builder()
                    .messages(SystemMessage.from(buildSystemPrompt()),
                              UserMessage.from(buildEvalPrompt(userQuery, intent, products)))
                    .maxOutputTokens(128)
                    .build());
            LLMCallResult<String> r = LLMCallResult.fromChat(resp, start);
            ProductEvaluationResult result = parseScores(r.result());

            feedbackService.submit(traceId, "relevance",        result.relevance(),       "");
            feedbackService.submit(traceId, "budget_adherence", result.budgetAdherence(),  "");
            feedbackService.submit(traceId, "intent_accuracy",  result.intentAccuracy(),   result.summary());

            log.info("Judge — traceId={} relevance={} budget={} intent={} summary='{}'",
                    traceId, result.relevance(), result.budgetAdherence(),
                    result.intentAccuracy(), result.summary());
        } catch (Exception e) {
            log.warn("Judge evaluation failed for traceId={}: {}", traceId, e.getMessage());
        }
    }

    private String buildSystemPrompt() {
        return "You are evaluating an e-commerce product search pipeline. " +
               "Score the pipeline output on 3 criteria, each 0.0 (poor) to 1.0 (perfect). " +
               "Return ONLY valid JSON — no explanation outside the JSON. " +
               "Format: {\"relevance\":0.9,\"budget_adherence\":1.0,\"intent_accuracy\":0.85," +
               "\"summary\":\"one sentence\"}";
    }

    private String buildEvalPrompt(String userQuery, ProductSearchIntent intent,
                                   List<ProductSearchResponse.Product> products) {
        StringBuilder sb = new StringBuilder("User query: \"").append(userQuery).append("\"\n\n");

        sb.append("Parsed intent:\n");
        sb.append("- Category:   ").append(intent.getCategoryId() != null ? intent.getCategoryId() : "not stated").append("\n");
        sb.append("- Brand:      ").append(
                intent.getBrandCodes() != null && !intent.getBrandCodes().isEmpty()
                        ? String.join(", ", intent.getBrandCodes())
                        : "not stated").append("\n");
        sb.append("- Max budget: ").append(intent.getMaxPrice() != null ? "$" + intent.getMaxPrice() : "not stated").append("\n");
        sb.append("- Min budget: ").append(intent.getMinPrice() != null ? "$" + intent.getMinPrice() : "not stated").append("\n");

        StringBuilder hard = new StringBuilder();
        if (Boolean.TRUE.equals(intent.getFreeShippingRequired())) hard.append("free shipping ");
        if (Boolean.TRUE.equals(intent.getWarrantyRequired()))     hard.append("warranty ");
        sb.append("- Required:   ").append(hard.length() == 0 ? "none" : hard.toString().trim()).append("\n\n");

        sb.append("Returned products:\n");
        for (int i = 0; i < products.size(); i++) {
            ProductSearchResponse.Product p = products.get(i);
            sb.append(String.format("%d. %s | %s | %s | $%.0f%n",
                    i + 1,
                    p.getName() != null ? p.getName() : "(unnamed)",
                    p.getBrandName() != null ? p.getBrandName() : "?",
                    p.getCategory() != null ? p.getCategory() : "?",
                    p.getPrice()));
        }
        sb.append("\nCriteria:\n")
          .append("- relevance:        do the products match the category and use case the user described?\n")
          .append("- budget_adherence: are all prices within the stated budget (or no budget stated)?\n")
          .append("- intent_accuracy:  did the parser correctly extract category, brand, price, and required features?\n");
        return sb.toString();
    }

    private ProductEvaluationResult parseScores(String llmResponse) {
        try {
            int start = llmResponse.indexOf('{');
            int end = llmResponse.lastIndexOf('}');
            if (start >= 0 && end > start) {
                JsonNode node = objectMapper.readTree(llmResponse.substring(start, end + 1));
                return new ProductEvaluationResult(
                        node.path("relevance").asDouble(0.0),
                        node.path("budget_adherence").asDouble(0.0),
                        node.path("intent_accuracy").asDouble(0.0),
                        node.path("summary").asText(""));
            }
        } catch (Exception e) {
            log.warn("Could not parse judge scores from response '{}': {}", llmResponse, e.getMessage());
        }
        return new ProductEvaluationResult(0.0, 0.0, 0.0, "parse error");
    }
}
