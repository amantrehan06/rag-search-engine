package com.productsearch.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.productsearch.model.Category;
import com.productsearch.model.IngestionResult;
import com.productsearch.service.CategoryPineconeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;

@Slf4j
@Service
public class CategoryIngestionService  {

    private static final String NAMESPACE = "categorynamespace";

    private final CategoryPineconeService categoryPineconeService;
    private final ObjectMapper objectMapper;

    @Autowired
    public CategoryIngestionService(CategoryPineconeService categoryPineconeService) {
        this.categoryPineconeService = categoryPineconeService;
        this.objectMapper = new ObjectMapper();
        log.info("CategoryIngestionService initialised");
    }

    public IngestionResult ingestCategories() {
        long start = System.currentTimeMillis();
        int processed = 0;
        int upserted  = 0;
        try {
            log.info("=== CATEGORY INGESTION START ===");

            List<Category> categories = loadCategories();
            processed = categories.size();
            log.info("Loaded {} categories from category.json", processed);

            categoryPineconeService.storeCategoryEmbeddings(categories);
            upserted = processed; // storeCategoryEmbeddings throws on any failure

            long durationMs = System.currentTimeMillis() - start;
            log.info("=== CATEGORY INGESTION END === upserted={} durationMs={}", upserted, durationMs);
            return IngestionResult.success(NAMESPACE, processed, upserted, durationMs);

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - start;
            log.error("Category ingestion failed after {} upserts: {}", upserted, e.getMessage(), e);
            return IngestionResult.failure(NAMESPACE, processed, upserted, durationMs, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<Category> loadCategories() throws Exception {
        ClassPathResource resource = new ClassPathResource("category.json");
        try (InputStream stream = resource.getInputStream()) {
            return objectMapper.readValue(stream, new TypeReference<List<Category>>() {});
        }
    }
}
