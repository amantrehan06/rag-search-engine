package com.productsearch.model;

import java.time.LocalDateTime;

public record IngestionResult(
        boolean success,
        String namespace,
        int recordsProcessed,
        int recordsUpserted,
        long durationMs,
        String timestamp,
        String error
) {

    public static IngestionResult success(String namespace,
                                          int recordsProcessed,
                                          int recordsUpserted,
                                          long durationMs) {
        return new IngestionResult(
                true, namespace, recordsProcessed, recordsUpserted,
                durationMs, LocalDateTime.now().toString(), null);
    }

    public static IngestionResult failure(String namespace,
                                          int recordsProcessed,
                                          int recordsUpserted,
                                          long durationMs,
                                          String error) {
        return new IngestionResult(
                false, namespace, recordsProcessed, recordsUpserted,
                durationMs, LocalDateTime.now().toString(), error);
    }
}
