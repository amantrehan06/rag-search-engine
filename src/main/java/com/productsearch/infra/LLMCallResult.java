package com.productsearch.infra;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

public record LLMCallResult<T>(
        T result,
        String rawJson,
        String modelName,
        int inputTokens,
        int outputTokens,
        long durationMs
) {
    public static LLMCallResult<String> fromChat(ChatResponse r, long startMs) {
        return fromChat(r, startMs, r.aiMessage().text());
    }

    public static <T> LLMCallResult<T> fromChat(ChatResponse r, long startMs, T result) {
        TokenUsage u = r.tokenUsage();
        return new LLMCallResult<>(
                result,
                null,
                r.modelName(),
                u != null && u.inputTokenCount()  != null ? u.inputTokenCount()  : 0,
                u != null && u.outputTokenCount() != null ? u.outputTokenCount() : 0,
                System.currentTimeMillis() - startMs);
    }
}
