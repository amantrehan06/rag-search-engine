package com.productsearch.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductSearchIntent {

    // === CORE SEARCH CRITERIA ===
    private String categoryId;         // catalog category id — set when user names a category explicitly
    private List<String> brandCodes;   // explicit brand mentions (e.g. ["SONY", "BOSE"]) — only catalog-known brands.
                                       // Multiple → OR semantics (Pinecone $in). null/empty → no brand filter.

    // categoryId is the metadata filter side. categorySearchQuery is the
    // vector-search side — used only when the user expresses a vibe/use case
    // without naming a category ("something for my home office"). Routing:
    //   • categoryId            → metadata filter against the products namespace
    //   • categorySearchQuery   → Pinecone vector search against the categories namespace
    //                             to resolve a category id
    private String categorySearchQuery;        // Rich 1-2 sentence description of the ideal product category.
                                               // Set ONLY when user expresses a vibe/use case without naming a category.
                                               // e.g. "comfortable seating for a small living room"

    // productSearchQuery → Pinecone vector search against the products namespace
    //   (soft: tier, features, overall product story)
    // requireFreeShipping / requireWarranty → metadata hard filters
    // (set only when user is explicit)
    private String productSearchQuery;         // Rich 1-2 sentence description of the ideal product.
                                               // e.g. "Premium noise-cancelling over-ear headphones for daily commuting"
                                               // null → neutral fallback query (all tiers ranked equally)

    // Hard feature requirements — metadata filters, not soft preferences.
    // Set to true ONLY when the user explicitly requires the feature
    // ("I need free shipping", "must include warranty"). Leave null when not
    // mentioned — do NOT default to false.
    private Boolean freeShippingRequired;
    private Boolean warrantyRequired;

    // === PRICE PREFERENCES ===
    private BigDecimal maxPrice;
    private BigDecimal minPrice;

    // === INTENT METADATA ===
    private Double confidence;
    private String originalQuery;
    private List<String> missingFields;
}
