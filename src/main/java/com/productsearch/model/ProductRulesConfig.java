package com.productsearch.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ProductRulesConfig {

    private List<CategoryEntry> categories;

    private Map<String, BrandEntry> brands;

    private List<Product> products;

    private List<VariantOverride> variantOverrides;

    // ── Inner classes ─────────────────────────────────────────────────────────

    @Data
    public static class CategoryEntry {
        private String id;
        private String name;
    }

    @Data
    public static class BrandEntry {
        private String name;
        private String category;
    }

    @Data
    public static class Product {
        private String productId;
        private String category;
        private String brandCode;
        private String modelName;
        private double price;
        private boolean freeShipping;
        private boolean warrantyIncluded;
        private boolean inStock;
        private double rating;
        private List<String> variants;
    }

    @Data
    public static class VariantOverride {
        private String productId;
        private String variantLabel;
        private double price;
        private String reason;
    }
}
