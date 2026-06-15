package com.productsearch.model;

public record CatalogGenerationResult(
        boolean success,
        int     recordsGenerated,
        long    durationMs,
        String  outputPath,
        String  error
) {

    public static CatalogGenerationResult success(int recordsGenerated, long durationMs, String outputPath) {
        return new CatalogGenerationResult(true, recordsGenerated, durationMs, outputPath, null);
    }

    public static CatalogGenerationResult failure(String error, long durationMs) {
        return new CatalogGenerationResult(false, 0, durationMs, null, error);
    }
}
