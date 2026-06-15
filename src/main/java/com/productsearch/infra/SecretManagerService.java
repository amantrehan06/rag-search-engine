package com.productsearch.infra;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SecretManagerService {

    public String getOpenAIApiKey() {
        return readSecret("OPENAI_API_KEY", "OpenAI API key");
    }

    public String getPineconeApiKey() {
        return readSecret("PINECONE_API_KEY", "Pinecone API key");
    }

    private String readSecret(String name, String description) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) value = System.getenv(name);
        if (value == null || value.isBlank()) {
            log.error("{} not found in system properties or environment variables", description);
            throw new RuntimeException(name + " is required. Set as -D" + name + "=... or an env var.");
        }
        return value;
    }
}
