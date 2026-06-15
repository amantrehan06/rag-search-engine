package com.productsearch.pipeline;

import com.productsearch.infra.PineconeIndex;
import com.productsearch.model.ProductSearchResponse;
import com.productsearch.model.ProductSearchSteps;

import java.util.List;

public record SearchResult(
        List<PineconeIndex.Match> candidates,
        List<ProductSearchResponse.Product> products,
        ProductSearchSteps.ProductSearchStep step
) {}
