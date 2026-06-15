package com.productsearch.eval;

import com.productsearch.ProductSearchTestApplication;
import com.productsearch.model.ProductSearchRequest;
import com.productsearch.model.ProductSearchResponse;
import com.productsearch.pipeline.SearchPipeline;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ProductSearchTestApplication.class)
@EnabledIf("com.productsearch.eval.EvalCredentials#available")
class ProductSearchEvalTest {

    @Autowired
    private SearchPipeline searchPipeline;

    @BeforeAll
    static void warmUp() throws InterruptedException {
        Thread.sleep(6_000);
    }

    // -------------------------------------------------------------------------
    // Eval 1: Both filters active (tier + price) — semantic + metadata
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Eval 1 | Premium running shoes under $200 — all FOOTWEAR / Premium / ≤$200")
    void premiumShoesUnder200_satisfiesAllFilters() {
        ProductSearchResponse response = ask("Premium running shoes under $200");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getProducts()).isNotEmpty();
        assertThat(response.getProducts()).allSatisfy(p -> {
            assertThat(p.getCategory()).as("category should be footwear").isEqualTo("footwear");
            assertThat(p.getPrice()).as("%s costs $%.0f, exceeds $200", p.getName(), p.getPrice())
                    .isLessThanOrEqualTo(200.0);
        });
    }

    // -------------------------------------------------------------------------
    // Eval 2: Adversarial price boundary — engineered $419 must be excluded
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Eval 2 | Premium headphones under $400 — Sony WH-1000XM5 v9 ($419) EXCLUDED")
    void premiumHeadphonesUnder400_excludesAdv1() {
        ProductSearchResponse response = ask("Premium headphones under $400");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getProducts()).isNotEmpty();
        assertThat(response.getProducts()).allSatisfy(p -> {
            assertThat(p.getCategory()).isEqualTo("audio");
            assertThat(p.getPrice())
                    .as("%s costs $%.0f — must be ≤ $400 (ADV 1: Sony WH-1000XM5 v9 at $419 must be filtered out)",
                            p.getName(), p.getPrice())
                    .isLessThanOrEqualTo(400.0);
        });
        // Explicit check: the engineered $419 variant must never appear in results.
        assertThat(response.getProducts())
                .extracting(ProductSearchResponse.Product::getPrice)
                .as("ADV 1 boundary product priced at $419 must be filtered out")
                .doesNotContain(419.0);
    }

    // -------------------------------------------------------------------------
    // Eval 3: Hard feature filter — warranty
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Eval 3 | Audio with warranty — all products have warrantyIncluded=true")
    void audioWithWarranty_allHaveWarranty() {
        ProductSearchResponse response = ask("Audio products with extended warranty");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getProducts()).isNotEmpty();
        assertThat(response.getProducts()).allSatisfy(p -> {
            assertThat(p.getCategory()).isEqualTo("audio");
            assertThat(p.isWarrantyIncluded())
                    .as("%s must have warranty included", p.getName())
                    .isTrue();
        });
    }

    // -------------------------------------------------------------------------
    // Eval 4: Out-of-catalog query — hallucination prevention
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Eval 4 | Vintage typewriters (not in catalog) — empty results, no hallucination")
    void vintageTypewriters_returnsNoProducts() {
        ProductSearchResponse response = ask("Vintage typewriters from the 1950s");

        // Success=true is still expected — the service responded gracefully
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getProducts())
                .as("typewriters are not in the catalog — system must not hallucinate")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // Eval 5: Semantic precedence — vague query routes via category lookup
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Eval 5 | Vague home-office query routes to FURNITURE via semantic category lookup")
    void homeOfficeQuery_routesToFurniture() {
        ProductSearchResponse response = ask("Something comfortable for my home office to sit and work");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getProducts()).isNotEmpty();
        // Top result should be from FURNITURE — the category lookup resolves the vibe.
        assertThat(response.getProducts().get(0).getCategory())
                .as("vague 'comfortable home office' should route to furniture")
                .isEqualTo("furniture");
    }

    // -------------------------------------------------------------------------
    // Eval 6: Multi-filter composition — category + price + hard feature
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Eval 6 | Running shoes with free shipping under $200 — all 3 constraints satisfied")
    void runningShoesFreeShippingUnder200_allConstraints() {
        ProductSearchResponse response = ask("Running shoes with free shipping under $200");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getProducts()).isNotEmpty();
        assertThat(response.getProducts()).allSatisfy(p -> {
            assertThat(p.getCategory()).isEqualTo("footwear");
            assertThat(p.getPrice()).isLessThanOrEqualTo(200.0);
            assertThat(p.isFreeShipping())
                    .as("%s must have free shipping", p.getName())
                    .isTrue();
        });
    }

    // -------------------------------------------------------------------------
    // Eval 7: "Best" / flagship audio — must stay in the audio category
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Eval 7 | 'Best audio equipment' — all returned products are audio")
    void bestAudio_staysInCategory() {
        ProductSearchResponse response = ask("Best audio equipment available, top-of-the-line");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getProducts()).isNotEmpty();
        assertThat(response.getProducts()).allSatisfy(p ->
                assertThat(p.getCategory()).isEqualTo("audio"));
    }

    // -------------------------------------------------------------------------
    // Eval 8: Implicit category — "marathon training" → footwear without "shoes"
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Eval 8 | 'Marathon training' routes to FOOTWEAR without mentioning shoes")
    void marathonTraining_routesToFootwear() {
        ProductSearchResponse response = ask("Quality items for marathon training and long distance running");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getProducts()).isNotEmpty();
        assertThat(response.getProducts()).allSatisfy(p ->
                assertThat(p.getCategory())
                        .as("'marathon training' should semantically map to footwear")
                        .isEqualTo("footwear"));
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private ProductSearchResponse ask(String query) {
        return searchPipeline.processProductSearch(
                ProductSearchRequest.builder()
                        .message(query)
                        .conversationId(java.util.UUID.randomUUID().toString())
                        .build());
    }
}
