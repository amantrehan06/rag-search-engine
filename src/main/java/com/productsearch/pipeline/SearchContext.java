package com.productsearch.pipeline;

import com.productsearch.infra.PineconeIndex;
import com.productsearch.model.ProductSearchIntent;
import com.productsearch.model.ProductSearchRequest;
import com.productsearch.model.ProductSearchResponse;
import com.productsearch.model.ProductSearchSteps;
import com.productsearch.model.RerankResult;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SearchContext {

    public ProductSearchRequest request;
    public String sessionId;
    public String combinedQuery;

    public Span rootSpan;
    public Scope rootScope;

    public ProductSearchIntent intent;
    public String llmJsonResponse;
    public Double intentConfidence;

    public List<String> queryVariations = new ArrayList<>();

    public String resolvedCategoryId;
    public String embeddingQueryUsed;
    public String searchMethodUsed;
    public List<String> topCategoryResults = new ArrayList<>();
    public double categoryConfidence;

    public List<PineconeIndex.Match> candidates = new ArrayList<>();
    public List<ProductSearchResponse.Product> products = new ArrayList<>();

    public RerankResult rerankResult;
    public List<ProductSearchResponse.Product> rankedResults = new ArrayList<>();

    public ProductSearchSteps.ProductSearchStepsBuilder stepsBuilder = ProductSearchSteps.builder();
    public ProductSearchResponse response;

    public static SearchContext of(ProductSearchRequest request) {
        SearchContext ctx = new SearchContext();
        ctx.request = request;
        ctx.sessionId = (request.getConversationId() != null && !request.getConversationId().isBlank())
                ? request.getConversationId()
                : (request.getSessionId() != null && !request.getSessionId().isBlank())
                ? request.getSessionId()
                : UUID.randomUUID().toString();
        return ctx;
    }
}
