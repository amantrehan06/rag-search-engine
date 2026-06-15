package com.productsearch.config;

import com.productsearch.infra.SecretManagerService;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class OpenAiClientsConfig {

    @Value("${openai.model.intent}")    private String intentSpec;
    @Value("${openai.model.expansion}") private String expansionSpec;
    @Value("${openai.model.reranker}")  private String rerankerSpec;
    @Value("${openai.model.judge}")     private String judgeSpec;
    @Value("${openai.model.catalog}")   private String catalogSpec;
    @Value("${openai.model.embedding}") private String embeddingModelName;

    @Bean
    public OpenAiEmbeddingModel openAiEmbeddingModel(SecretManagerService secrets) {
        return OpenAiEmbeddingModel.builder()
                .apiKey(secrets.getOpenAIApiKey())
                .modelName(embeddingModelName)
                .build();
    }

    @Bean("intentModel")
    public OpenAiChatModel intentModel(SecretManagerService secrets)    { return chat(secrets, ModelSpec.parse(intentSpec)); }

    @Bean("expansionModel")
    public OpenAiChatModel expansionModel(SecretManagerService secrets) { return chat(secrets, ModelSpec.parse(expansionSpec)); }

    @Bean("rerankerModel")
    public OpenAiChatModel rerankerModel(SecretManagerService secrets)  { return chat(secrets, ModelSpec.parse(rerankerSpec)); }

    @Bean("judgeModel")
    public OpenAiChatModel judgeModel(SecretManagerService secrets)     { return chat(secrets, ModelSpec.parse(judgeSpec)); }

    @Bean("catalogModel")
    public OpenAiChatModel catalogModel(SecretManagerService secrets)   { return chat(secrets, ModelSpec.parse(catalogSpec)); }

    private static OpenAiChatModel chat(SecretManagerService secrets, ModelSpec spec) {
        return OpenAiChatModel.builder()
                .apiKey(secrets.getOpenAIApiKey())
                .modelName(spec.name())
                .temperature(spec.temperature())
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    private record ModelSpec(String name, double temperature) {
        static ModelSpec parse(String raw) {
            int colon = raw.indexOf(':');
            if (colon < 0) return new ModelSpec(raw.trim(), 0.0);
            String name = raw.substring(0, colon).trim();
            String t = raw.substring(colon + 1).trim();
            return new ModelSpec(name, t.isEmpty() ? 0.0 : Double.parseDouble(t));
        }
    }
}
