package com.productsearch.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductSearchRequest {
    private String message;
    private String conversationId;
    private String sessionId;
    private Double minSimilarityScore; // Minimum similarity score for semantic search (0.0 to 1.0)
}
