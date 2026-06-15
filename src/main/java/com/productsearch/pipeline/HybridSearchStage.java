package com.productsearch.pipeline;

import com.productsearch.constants.ProductSearchConstants;
import com.productsearch.infra.PineconeIndex;
import com.productsearch.model.ProductSearchIntent;
import com.productsearch.model.ProductSearchResponse;
import com.productsearch.model.ProductSearchSteps;
import com.productsearch.service.ProductPineconeService;
import com.productsearch.tracing.ProductPipelineTracer;
import io.opentelemetry.api.trace.Span;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Component
@RequiredArgsConstructor
public class HybridSearchStage {

    private static final int PER_VARIATION_LIMIT = 10;
    private static final int RRF_K = 60;

    private final ProductPineconeService pinecone;
    private final ProductPipelineTracer tracer;
    @Qualifier("searchExpansionExecutor")
    private final Executor expansionExecutor;

    public void run(SearchContext ctx) {
        if (ctx.resolvedCategoryId == null) {
            ctx.stepsBuilder.productSearch(ProductSearchSteps.ProductSearchStep.builder()
                    .metadataFilter(new HashMap<>())
                    .embeddingSearchQuery(ProductSearchConstants.NO_SEARCH_QUERY)
                    .resultsCount(0)
                    .searchMethod(ProductSearchConstants.NO_SEARCH_METHOD)
                    .timestamp(LocalDateTime.now().toString())
                    .build());
            return;
        }

        Map<String, Object> filter = buildMetadataFilter(ctx.intent);
        List<String> queries = ctx.queryVariations;
        String traceQuery = ctx.combinedQuery;

        int count = tracer.traceProductSearch(traceQuery, () -> {
            // Each variation hits Pinecone independently and returns its own ranked list of matches.
            // We need per-query rankings (not a flat union) so RRF can score by per-query rank.
            List<List<PineconeIndex.Match>> perQuery = runSearches(queries, filter);

            // RRF merges the N ranked lists into one consensus-ordered list. The product the
            // reranker sees first is the one with the strongest cross-variation agreement, not
            // the one with the single highest cosine score from any one variation.
            List<PineconeIndex.Match> fused = fuseRRF(perQuery);

            int beforeDedup = perQuery.stream().mapToInt(List::size).sum();
            Map<String, Long> occurrences = perQuery.stream()
                    .flatMap(List::stream)
                    .collect(Collectors.groupingBy(PineconeIndex.Match::id, Collectors.counting()));
            int inAll      = (int) occurrences.values().stream().filter(c -> c == queries.size()).count();
            int inMajority = (int) occurrences.values().stream().filter(c -> c >  queries.size() / 2).count();

            Span span = Span.current();
            span.setAttribute("search.query_variations",             (long) queries.size());
            span.setAttribute("search.candidates_before_dedup",      (long) beforeDedup);
            span.setAttribute("search.candidates_after_dedup",       (long) fused.size());
            span.setAttribute("search.fusion_method",                "RRF");
            span.setAttribute("search.candidates_in_all_variations", (long) inAll);
            span.setAttribute("search.candidates_in_majority",       (long) inMajority);

            ctx.candidates = fused;
            return ctx.candidates.size();
        });
        ctx.products = toProducts(ctx.candidates);

        ctx.stepsBuilder.productSearch(ProductSearchSteps.ProductSearchStep.builder()
                .metadataFilter(filter)
                .embeddingSearchQuery(traceQuery)
                .resultsCount(count)
                .searchMethod(ProductSearchConstants.HYBRID_SEARCH_METHOD)
                .timestamp(LocalDateTime.now().toString())
                .build());
    }

    private List<List<PineconeIndex.Match>> runSearches(List<String> queries, Map<String, Object> filter) {
        if (queries.size() == 1) {
            return List.of(pinecone.search(queries.get(0), PER_VARIATION_LIMIT, filter));
        }
        List<CompletableFuture<List<PineconeIndex.Match>>> futures = IntStream.range(0, queries.size())
                .mapToObj(i -> CompletableFuture.supplyAsync(
                        () -> tracer.traceVariation(i, queries.get(i),
                                () -> pinecone.search(queries.get(i), PER_VARIATION_LIMIT, filter)),
                        expansionExecutor))
                .toList();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    // Reciprocal Rank Fusion (Cormack et al., 2009).
    //
    // WHY rank, not score: cosine similarity scales differ across queries — one variation
    // may cluster tightly (0.80-0.92), another loosely (0.55-0.72). Summing or maxing
    // absolute scores would systematically favour queries whose embeddings happen to land
    // in dense regions, not candidates that match better. Rank is invariant to that.
    //
    // WHY sum across appearances: this is the consensus mechanism. A product at rank 5 in
    // every variation outscores a product at rank 1 in only one. Three independent angles
    // agreeing on "this is relevant" is a stronger signal than one angle's lottery winner.
    //
    // WHY k = 60: from the original paper. Dampens contribution of low-ranked docs while
    // keeping their weight non-zero. Empirically validated; used by Elastic, Vespa, and
    // Pinecone hybrid search. Treat as a constant unless you have eval data to retune it.
    //
    // The Match we carry forward (`repr`) is just for metadata downstream — the reranker
    // reads name/price/etc., not the cosine score. RRF order is the actual ranking signal.
    private static List<PineconeIndex.Match> fuseRRF(List<List<PineconeIndex.Match>> perQuery) {
        Map<String, Double> score = new HashMap<>();
        Map<String, PineconeIndex.Match> repr = new HashMap<>();
        for (List<PineconeIndex.Match> matches : perQuery) {
            for (int rank = 0; rank < matches.size(); rank++) {
                PineconeIndex.Match m = matches.get(rank);
                score.merge(m.id(), 1.0 / (RRF_K + rank + 1), Double::sum);
                repr.putIfAbsent(m.id(), m);
            }
        }
        return score.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> repr.get(e.getKey()))
                .toList();
    }

    private static Map<String, Object> buildMetadataFilter(ProductSearchIntent intent) {
        Map<String, Object> filter = new HashMap<>();
        Map<String, Object> price = new HashMap<>();

        if (intent.getCategoryId() != null) {
            filter.put(ProductSearchConstants.CATEGORY_FIELD,
                    Map.of(ProductSearchConstants.EQUALS_OPERATOR, intent.getCategoryId()));
        }

        double min = intent.getMinPrice() != null ? intent.getMinPrice().doubleValue() : ProductSearchConstants.DEFAULT_MIN_PRICE;
        double max = intent.getMaxPrice() != null ? intent.getMaxPrice().doubleValue() : ProductSearchConstants.DEFAULT_MAX_PRICE;
        price.put(ProductSearchConstants.GREATER_THAN_EQUAL_OPERATOR, min);
        price.put(ProductSearchConstants.LESS_THAN_EQUAL_OPERATOR, max);
        filter.put(ProductSearchConstants.PRICE_FIELD, price);

        if (intent.getBrandCodes() != null && !intent.getBrandCodes().isEmpty()) {
            filter.put(ProductSearchConstants.BRAND_CODE_FIELD,
                    intent.getBrandCodes().size() == 1
                            ? Map.of(ProductSearchConstants.EQUALS_OPERATOR, intent.getBrandCodes().get(0))
                            : Map.of(ProductSearchConstants.IN_OPERATOR, intent.getBrandCodes()));
        }
        if (Boolean.TRUE.equals(intent.getFreeShippingRequired())) {
            filter.put(ProductSearchConstants.FREE_SHIPPING_FIELD,
                    Map.of(ProductSearchConstants.EQUALS_OPERATOR, "true"));
        }
        if (Boolean.TRUE.equals(intent.getWarrantyRequired())) {
            filter.put(ProductSearchConstants.WARRANTY_INCLUDED_FIELD,
                    Map.of(ProductSearchConstants.EQUALS_OPERATOR, "true"));
        }
        return filter;
    }

    private static List<ProductSearchResponse.Product> toProducts(List<PineconeIndex.Match> matches) {
        List<ProductSearchResponse.Product> out = new ArrayList<>(matches.size());
        for (PineconeIndex.Match m : matches) {
            Map<String, Object> meta = m.metadata();
            if (meta.isEmpty()) continue;
            out.add(ProductSearchResponse.Product.builder()
                    .productId(str(meta, ProductSearchConstants.ID_KEY))
                    .name(str(meta, ProductSearchConstants.NAME_KEY))
                    .category(str(meta, ProductSearchConstants.CATEGORY_KEY))
                    .brandName(str(meta, ProductSearchConstants.BRAND_NAME_KEY))
                    .brandCode(str(meta, ProductSearchConstants.BRAND_CODE_KEY))
                    .price(num(meta, ProductSearchConstants.PRICE_KEY))
                    .description(str(meta, ProductSearchConstants.DESCRIPTION_KEY))
                    .variantLabel(str(meta, ProductSearchConstants.VARIANT_LABEL_KEY))
                    .features(features(meta))
                    .warrantyIncluded("true".equals(str(meta, ProductSearchConstants.WARRANTY_INCLUDED_KEY)))
                    .freeShipping("true".equals(str(meta, ProductSearchConstants.FREE_SHIPPING_KEY)))
                    .build());
        }
        return out;
    }

    private static List<String> features(Map<String, Object> meta) {
        List<String> f = new ArrayList<>();
        if ("true".equals(str(meta, ProductSearchConstants.FREE_SHIPPING_KEY))) f.add("Free Shipping");
        if ("true".equals(str(meta, ProductSearchConstants.WARRANTY_INCLUDED_KEY))) f.add("Warranty Included");
        return f;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    private static double num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return 0.0;
        try { return Double.parseDouble(v.toString()); } catch (NumberFormatException e) { return 0.0; }
    }
}
