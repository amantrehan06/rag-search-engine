package com.productsearch.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductSearchResponse {
    private boolean success;
    private String message;
    private String response;
    private String conversationId;
    private List<Product> products;
    private ProductSearchSteps steps; // Step-by-step debug information

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Product {
        private String productId;
        private String name;
        private String category;
        private String brandCode;
        private String brandName;
        private double price;
        private String description;
        private List<String> features;
        private String variantLabel;   // e.g. "Black", "Midnight Blue", "Touch Model"

        // Individual feature flags for UI display
        private boolean freeShipping;
        private boolean warrantyIncluded;
    }
}
