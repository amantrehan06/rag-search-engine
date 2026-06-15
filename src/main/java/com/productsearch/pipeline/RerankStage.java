package com.productsearch.pipeline;

import com.productsearch.model.RerankResult;
import com.productsearch.service.ProductRerankerService;
import com.productsearch.tracing.ProductPipelineTracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RerankStage {

    private static final int TOP_N = 5;

    private final ProductRerankerService reranker;
    private final ProductPipelineTracer tracer;

    public void run(SearchContext ctx) {
        if (ctx.products.isEmpty()) {
            ctx.rerankResult = RerankResult.empty();
            ctx.rankedResults = List.of();
            return;
        }
        ctx.rerankResult = tracer.traceReranker(
                () -> reranker.rerankWithUsage(ctx.combinedQuery, ctx.products, TOP_N));
        ctx.rankedResults = ctx.rerankResult.products();
    }
}
