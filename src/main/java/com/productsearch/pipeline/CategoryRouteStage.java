package com.productsearch.pipeline;

import com.productsearch.constants.ProductSearchConstants;
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

    public void run(SearchContext ctx) {
        if (ctx.intent.getCategoryId() != null && !ctx.intent.getCategoryId().isBlank()) {
            ctx.resolvedCategoryId = ctx.intent.getCategoryId();
            ctx.topCategoryResults = List.of(ctx.resolvedCategoryId);
            ctx.embeddingQueryUsed = "Direct category specification: " + ctx.resolvedCategoryId;
            ctx.searchMethodUsed = ProductSearchConstants.DIRECT_CATEGORY_SPECIFICATION_METHOD;
            ctx.categoryConfidence = 1.0;
        } else if (ctx.intent.getCategorySearchQuery() != null && !ctx.intent.getCategorySearchQuery().isBlank()) {
            ctx.embeddingQueryUsed = ctx.intent.getCategorySearchQuery();
            ctx.searchMethodUsed = "Pinecone semantic search";
            ctx.topCategoryResults = tracer.traceCategorySearch(ctx.embeddingQueryUsed,
                    () -> new ArrayList<>(new LinkedHashSet<>(
                            categories.searchCategoriesSemantically(ctx.embeddingQueryUsed, 2))));
            if (!ctx.topCategoryResults.isEmpty()) {
                ctx.resolvedCategoryId = ctx.topCategoryResults.get(0);
                ctx.intent.setCategoryId(ctx.resolvedCategoryId);
                ctx.categoryConfidence = ctx.topCategoryResults.size() == 1 ? 1.0 : 0.7;
            } else {
                ctx.categoryConfidence = 0.0;
            }
        } else {
            ctx.embeddingQueryUsed = "No category search — missing category information";
            ctx.searchMethodUsed = ProductSearchConstants.NO_SEARCH_PERFORMED_METHOD;
            ctx.categoryConfidence = 0.0;
        }

        ctx.stepsBuilder.categorySearch(ProductSearchSteps.CategorySearchStep.builder()
                .categoryId(ctx.resolvedCategoryId != null ? ctx.resolvedCategoryId : "none")
                .embeddingQuery(ctx.embeddingQueryUsed)
                .topResults(ctx.topCategoryResults)
                .searchMethod(ctx.searchMethodUsed)
                .timestamp(LocalDateTime.now().toString())
                .build());
    }
}
