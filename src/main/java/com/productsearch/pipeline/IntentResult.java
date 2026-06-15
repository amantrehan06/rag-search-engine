package com.productsearch.pipeline;

import com.productsearch.model.ProductSearchIntent;
import com.productsearch.model.ProductSearchResponse;
import com.productsearch.model.ProductSearchSteps;

public record IntentResult(
        ProductSearchIntent intent,
        String rawJson,
        double confidence,
        ProductSearchSteps.IntentParserStep step,
        ProductSearchResponse followUpResponse   // non-null = early exit before search
) {}
