package com.productsearch.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductSearchSteps {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IntentParserStep {
        private String originalQuery;
        private String llmResponse; // Raw LLM response
        private String confidence;
        private List<String> missingFields;
        private String timestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategorySearchStep {
        private String categoryId;
        private String embeddingQuery;     // What was embedded for category search
        private List<String> topResults;   // Top results from Pinecone categorynamespace
        private String searchMethod;
        private String timestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductSearchStep {
        private Map<String, Object> metadataFilter; // Exact metadata filter query
        private String embeddingSearchQuery;        // Exact embedding search query
        private Integer resultsCount;
        private String searchMethod;
        private String timestamp;
    }

    private IntentParserStep intentParser;
    private CategorySearchStep categorySearch;
    private ProductSearchStep productSearch;
}
