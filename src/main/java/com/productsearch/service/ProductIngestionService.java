package com.productsearch.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.productsearch.constants.ProductSearchConstants;
import com.productsearch.infra.PineconeIndex;
import com.productsearch.model.IngestionResult;
import com.productsearch.model.ProductRecord;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class ProductIngestionService  {

    private static final String NAMESPACE         = PineconeIndex.PRODUCTS_NAMESPACE;
    private static final String PRODUCTS_JSON_SRC = "src/main/resources/products.json";
    private static final int INGEST_THREADS    = 20;

    private final OpenAiEmbeddingModel embeddingModel;
    private final PineconeIndex pineconeIndex;
    private final ObjectMapper objectMapper;

    @Autowired
    public ProductIngestionService(OpenAiEmbeddingModel embeddingModel,
                                       PineconeIndex pineconeIndex) {
        this.embeddingModel = embeddingModel;
        this.pineconeIndex = pineconeIndex;
        this.objectMapper = new ObjectMapper();
        log.info("ProductIngestionService initialised — reads products.json, writes to {}", NAMESPACE);
    }

    public IngestionResult ingestProducts() {
        long start = System.currentTimeMillis();
        int processed = 0;
        int upserted = 0;
        ExecutorService executor = null;
        try {
            List<ProductRecord> products = loadProductsJson();
            processed = products.size();
            if (processed == 0) {
                return IngestionResult.failure(NAMESPACE, 0, 0, System.currentTimeMillis() - start,
                        "products.json not found or empty. Run POST /api/v1/ingestion/generate-catalog first.");
            }
            log.info("Loaded {} records — embedding and upserting to {}", processed, NAMESPACE);

            final int total = processed;
            AtomicInteger counter = new AtomicInteger(0);
            executor = Executors.newFixedThreadPool(Math.min(INGEST_THREADS, total));

            List<CompletableFuture<Void>> futures = new ArrayList<>(total);
            for (ProductRecord record : products) {
                futures.add(CompletableFuture.runAsync(() -> {
                    upsertProduct(record);
                    int done = counter.incrementAndGet();
                    if (done % 25 == 0 || done == total) log.info("  upserted {}/{}", done, total);
                }, executor));
            }
            for (CompletableFuture<Void> f : futures) {
                f.get();
                upserted++;
            }
            return IngestionResult.success(NAMESPACE, processed, upserted, System.currentTimeMillis() - start);

        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("Product ingestion failed after {}/{}: {}", upserted, processed, cause.getMessage(), cause);
            return IngestionResult.failure(NAMESPACE, processed, upserted,
                    System.currentTimeMillis() - start, cause.getMessage());
        } finally {
            if (executor != null) executor.shutdownNow();
        }
    }

    private List<ProductRecord> loadProductsJson() throws Exception {
        TypeReference<List<ProductRecord>> typeRef = new TypeReference<>() {};

        File srcFile = new File(PRODUCTS_JSON_SRC);
        if (srcFile.exists() && srcFile.length() > 0) {
            log.info("Reading products.json from source tree: {}", srcFile.getAbsolutePath());
            try (InputStream is = new FileInputStream(srcFile)) {
                return objectMapper.readValue(is, typeRef);
            }
        }

        ClassPathResource cpr = new ClassPathResource("products.json");
        if (cpr.exists()) {
            log.info("Reading products.json from classpath");
            try (InputStream is = cpr.getInputStream()) {
                return objectMapper.readValue(is, typeRef);
            }
        }

        log.warn("products.json not found at '{}' or on classpath", PRODUCTS_JSON_SRC);
        return List.of();
    }

    private void upsertProduct(ProductRecord record) {
        String embeddingText = String.format(
                "Product: %s — %s variant. Category: %s. Brand: %s. %s Features: %s.",
                record.modelName(),
                record.variantLabel(),
                record.category(),
                record.brandName(),
                record.experienceDescription().trim(),
                describeFeatures(record));

        Embedding embedding = embeddingModel.embed(embeddingText).content();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ProductSearchConstants.ID_KEY,                record.productId());
        metadata.put(ProductSearchConstants.NAME_KEY,
                     record.brandName() + " " + record.modelName() + " — " + record.variantLabel());
        metadata.put(ProductSearchConstants.CATEGORY_KEY,          record.category());
        metadata.put(ProductSearchConstants.BRAND_CODE_KEY,        record.brandCode());
        metadata.put(ProductSearchConstants.BRAND_NAME_KEY,        record.brandName());
        metadata.put(ProductSearchConstants.DESCRIPTION_KEY,       record.experienceDescription().trim());
        metadata.put(ProductSearchConstants.VARIANT_LABEL_KEY,     record.variantLabel());
        metadata.put(ProductSearchConstants.FREE_SHIPPING_KEY,     String.valueOf(record.freeShipping()));
        metadata.put(ProductSearchConstants.WARRANTY_INCLUDED_KEY, String.valueOf(record.warrantyIncluded()));
        metadata.put(ProductSearchConstants.PRICE_KEY,             (int) record.price());
        metadata.put("text",        embeddingText);
        metadata.put("modelName",   record.modelName());
        metadata.put("inStock",     String.valueOf(record.inStock()));
        metadata.put("rating",      String.valueOf(record.rating()));

        pineconeIndex.upsert(NAMESPACE, record.productId(), embedding.vectorAsList(), metadata);
    }

    private String describeFeatures(ProductRecord record) {
        List<String> features = new ArrayList<>();
        if (record.freeShipping())    features.add("free shipping");
        if (record.warrantyIncluded()) features.add("extended warranty");
        return features.isEmpty() ? "no extras" : String.join(", ", features);
    }
}
