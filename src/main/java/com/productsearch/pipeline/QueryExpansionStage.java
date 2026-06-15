package com.productsearch.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.productsearch.infra.LLMCallResult;
import com.productsearch.tracing.ProductPipelineTracer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
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
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class QueryExpansionStage {

    private static final String EXPANSION_TOOL = "emit_query_variations";
    private static final String SYSTEM_PROMPT =
            "You rewrite a product search query into N semantically distinct retrieval queries for a " +
            "dense-vector index. Each rewrite must be a complete 1-2 sentence description of the ideal " +
            "product — same richness as the input, never a keyword bag. Cover use case, intended user, " +
            "quality cues, and lifestyle context. Each rewrite emphasises a different angle (use case, " +
            "persona, feature, value proposition, scenario).\n\n" +
            "Example input: \"Lightweight laptop for daily work under $1500.\"\n" +
            "Example rewrites:\n" +
            "1. \"Slim, lightweight ultrabook built for professionals who code, write, and join video calls all day from cafés and hotel rooms.\"\n" +
            "2. \"Mid-range business laptop balancing battery life, build quality, and a snappy keyboard for everyday productivity work.\"\n" +
            "3. \"Portable productivity machine for hybrid workers who need long unplugged sessions and a quiet, premium feel without flagship pricing.\"";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Qualifier("expansionModel")
    private final OpenAiChatModel chatModel;
    private final ProductPipelineTracer tracer;

    @Value("${search.query_variations:3}")
    private int variationCount;

    public ExpansionResult run(String combinedQuery) {
        if (variationCount <= 1) {
            return new ExpansionResult(List.of(combinedQuery));
        }
        List<String> variations = tracer.traceQueryExpansion(combinedQuery,
                () -> generateVariations(combinedQuery, variationCount)).result();
        return new ExpansionResult(variations);
    }

    private LLMCallResult<List<String>> generateVariations(String query, int n) {
        long start = System.currentTimeMillis();
        ChatResponse resp = chatModel.chat(ChatRequest.builder()
                .messages(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(query))
                .toolSpecifications(expansionTool(n))
                .toolChoice(ToolChoice.REQUIRED)
                .maxOutputTokens(Math.max(300, 100 * n))
                .build());

        List<ToolExecutionRequest> calls = resp.aiMessage().toolExecutionRequests();
        if (calls == null || calls.isEmpty()) {
            throw new IllegalStateException("LLM returned no tool call for query expansion");
        }

        JsonNode args = readJson(calls.get(0).arguments());
        JsonNode arr  = args.get("variations");
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            throw new IllegalStateException("Expansion tool returned no variations: " + args);
        }

        List<String> variations = new ArrayList<>(Math.min(arr.size(), n));
        for (JsonNode el : arr) {
            if (variations.size() >= n) break;
            String v = el.asText("").trim();
            if (!v.isEmpty()) variations.add(v);
        }
        if (variations.isEmpty()) {
            throw new IllegalStateException("Expansion tool returned only empty strings: " + args);
        }
        return LLMCallResult.fromChat(resp, start, variations);
    }

    private static ToolSpecification expansionTool(int n) {
        return ToolSpecification.builder()
                .name(EXPANSION_TOOL)
                .description("Emit exactly " + n + " distinct phrasings of the user's product search query.")
                .parameters(JsonObjectSchema.builder()
                        .addProperty("variations", JsonArraySchema.builder()
                                .description("Exactly " + n + " distinct rewrites of the user's query. " +
                                        "No numbering, no surrounding quotes, no duplicates.")
                                .items(JsonStringSchema.builder()
                                        .description("A complete 1-2 sentence retrieval query, 15-40 words. " +
                                                "Same richness as the input. Never a short keyword phrase.")
                                        .build())
                                .build())
                        .required("variations")
                        .build())
                .build();
    }

    private static JsonNode readJson(String s) {
        try { return JSON.readTree(s); }
        catch (Exception e) { throw new IllegalStateException("Failed to parse expansion arguments: " + s, e); }
    }
}
