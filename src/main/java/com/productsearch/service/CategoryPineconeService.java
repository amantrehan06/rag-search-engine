package com.productsearch.service;

import com.productsearch.infra.PineconeIndex;
import com.productsearch.model.Category;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class CategoryPineconeService  {

    private static final double CATEGORY_SCORE_THRESHOLD = 0.35;
    private static final String NAMESPACE                = PineconeIndex.CATEGORY_NAMESPACE;

    private final PineconeIndex pineconeIndex;
    private final OpenAiEmbeddingModel embeddingModel;

    @Autowired
    public CategoryPineconeService(PineconeIndex pineconeIndex,
                                       OpenAiEmbeddingModel embeddingModel) {
        this.pineconeIndex = pineconeIndex;
        this.embeddingModel = embeddingModel;
    }

    public void storeCategoryEmbeddings(List<Category> categories) {
        log.info("Storing {} categories in {}", categories.size(), NAMESPACE);
        for (Category category : categories) {
            String text = String.format("Category: %s. %s", category.getName(), category.getDescription());
            Embedding embedding = embeddingModel.embed(text).content();

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("categoryId",   category.getId());
            metadata.put("categoryName", category.getName());
            metadata.put("text",         text);
            metadata.put("type",         "category");

            String id = String.format("cat_%s_%s", category.getId(),
                    UUID.randomUUID().toString().substring(0, 8));
            pineconeIndex.upsert(NAMESPACE, id, embedding.vectorAsList(), metadata);
            log.info("Stored category embedding: {} ({})", category.getName(), id);
        }
    }

    public List<String> searchCategoriesSemantically(String query, int maxResults) {
        Embedding embedding = embeddingModel.embed(query).content();
        List<PineconeIndex.Match> matches =
                pineconeIndex.query(NAMESPACE, embedding.vectorAsList(), maxResults, 0.0, null);

        log.info("Category search '{}' — {} raw matches (threshold {})",
                query, matches.size(), CATEGORY_SCORE_THRESHOLD);

        List<String> results = new ArrayList<>();
        for (PineconeIndex.Match m : matches) {
            boolean passes = m.score() >= CATEGORY_SCORE_THRESHOLD;
            String text = String.valueOf(m.metadata().getOrDefault("text", ""));
            log.info("  score={} [{}] text='{}'", m.score(), passes ? "PASS" : "FAIL",
                    text.length() > 100 ? text.substring(0, 100) + "..." : text);
            if (!passes) continue;
            Object categoryId = m.metadata().get("categoryId");
            if (categoryId != null) results.add(categoryId.toString());
        }
        return results;
    }
}
