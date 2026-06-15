package com.productsearch.pipeline;

import com.productsearch.model.ProductSearchResponse;
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

    public RerankResult run(String combinedQuery, List<ProductSearchResponse.Product> products) {
        if (products.isEmpty()) {
            return RerankResult.empty();
        }
        return tracer.traceReranker(() -> reranker.rerankWithUsage(combinedQuery, products, TOP_N));
    }
}
