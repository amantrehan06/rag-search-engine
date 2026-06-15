package com.productsearch.pipeline;

import com.productsearch.constants.ProductSearchConstants;
import com.productsearch.model.ProductSearchIntent;
import com.productsearch.model.ProductSearchSteps;
import com.productsearch.service.CategoryPineconeService;
import com.productsearch.tracing.ProductPipelineTracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CategoryRouteStage {

    private final CategoryPineconeService categories;
    private final ProductPipelineTracer tracer;

    public CategoryResult run(ProductSearchIntent intent) {
        String resolvedCategoryId;
        List<String> topCategoryResults;
        String embeddingQueryUsed;
        String searchMethodUsed;
        double confidence;

        if (intent.getCategoryId() != null && !intent.getCategoryId().isBlank()) {
            resolvedCategoryId = intent.getCategoryId();
            topCategoryResults = List.of(resolvedCategoryId);
            embeddingQueryUsed = "Direct category specification: " + resolvedCategoryId;
            searchMethodUsed = ProductSearchConstants.DIRECT_CATEGORY_SPECIFICATION_METHOD;
            confidence = 1.0;
        } else if (intent.getCategorySearchQuery() != null && !intent.getCategorySearchQuery().isBlank()) {
            embeddingQueryUsed = intent.getCategorySearchQuery();
            searchMethodUsed = "Pinecone semantic search";
            topCategoryResults = tracer.traceCategorySearch(embeddingQueryUsed,
                    () -> new ArrayList<>(new LinkedHashSet<>(
                            categories.searchCategoriesSemantically(embeddingQueryUsed, 2))));
            if (!topCategoryResults.isEmpty()) {
                resolvedCategoryId = topCategoryResults.get(0);
                confidence = topCategoryResults.size() == 1 ? 1.0 : 0.7;
            } else {
                resolvedCategoryId = null;
                confidence = 0.0;
            }
        } else {
            resolvedCategoryId = null;
            topCategoryResults = List.of();
            embeddingQueryUsed = "No category search — missing category information";
            searchMethodUsed = ProductSearchConstants.NO_SEARCH_PERFORMED_METHOD;
            confidence = 0.0;
        }

        ProductSearchSteps.CategorySearchStep step = ProductSearchSteps.CategorySearchStep.builder()
                .categoryId(resolvedCategoryId != null ? resolvedCategoryId : "none")
                .embeddingQuery(embeddingQueryUsed)
                .topResults(topCategoryResults)
                .searchMethod(searchMethodUsed)
                .timestamp(LocalDateTime.now().toString())
                .build();

        return new CategoryResult(resolvedCategoryId, topCategoryResults, embeddingQueryUsed, searchMethodUsed, confidence, step);
    }
}
