package com.productsearch.model;

public record IntentParserResult(
        ProductSearchIntent intent,
        String rawJson,       // pretty-printed function-call arguments JSON for the UI panel
        String modelName,     // e.g. "gpt-4o-mini"
        int inputTokens,      // prompt_tokens from the OpenAI usage block
        int outputTokens,     // completion_tokens from the OpenAI usage block
        long durationMs       // wall-clock time for the full LLM call
) {}
