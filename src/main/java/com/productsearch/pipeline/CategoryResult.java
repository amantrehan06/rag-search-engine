package com.productsearch.pipeline;

import com.productsearch.model.ProductSearchSteps;

import java.util.List;

public record CategoryResult(
        String resolvedCategoryId,
        List<String> topCategoryResults,
        String embeddingQueryUsed,
        String searchMethodUsed,
        double confidence,
        ProductSearchSteps.CategorySearchStep step
) {}
