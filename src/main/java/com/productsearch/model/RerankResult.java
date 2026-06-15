package com.productsearch.model;

import java.util.List;

public record RerankResult(
        List<ProductSearchResponse.Product> products,
        String modelName,
        int inputTokens,
        int outputTokens,
        long durationMs,
        int candidatesIn,
        int candidatesOut
) {
    private static final String NO_MODEL = "none";

    public static RerankResult empty() {
        return new RerankResult(List.of(), NO_MODEL, 0, 0, 0, 0, 0);
    }

    public static RerankResult noRerank(int candidatesIn, List<ProductSearchResponse.Product> products) {
        return new RerankResult(products, NO_MODEL, 0, 0, 0, candidatesIn, products.size());
    }
}
