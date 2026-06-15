package com.productsearch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import com.productsearch.model.CatalogGenerationResult;
import com.productsearch.model.ProductRecord;
import com.productsearch.model.ProductRulesConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import com.fasterxml.jackson.core.type.TypeReference;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class CatalogGenerationService  {

    private static final String PRODUCTS_JSON_PATH = "src/main/resources/products.json";

    private static final String SYSTEM_PROMPT =
            "You are a product copywriter for a premium e-commerce catalog. \n" +
                    "Your job is to write short, specific descriptions that help \n" +
                    "customers understand exactly what it feels like to own and use \n" +
                    "a product. You write from real product knowledge, not marketing \n" +
                    "templates. Every description you write is meaningfully different \n" +
                    "from every other description you write.";
    private static final int DESCRIPTION_MAX_TOKENS = 200;

    private final OpenAiChatModel chatModel;
    private final ObjectMapper objectMapper;

    @Autowired
    public CatalogGenerationService(@Qualifier("catalogModel") OpenAiChatModel chatModel) {
        this.chatModel = chatModel;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        log.info("CatalogGenerationService initialised — output: {}", PRODUCTS_JSON_PATH);
    }

    public CatalogGenerationResult generateCatalog() {
        long start = System.currentTimeMillis();

        File outputFile = new File(PRODUCTS_JSON_PATH);

        try {

            ProductRulesConfig rules = loadRules();
            log.info("Step A complete — categories={} brands={} products={}",
                    rules.getCategories().size(),
                    rules.getBrands().size(),
                    rules.getProducts().size());

            Map<String, ProductRulesConfig.VariantOverride> overrideIndex = buildOverrideIndex(rules);

            List<RecordParams> params = expandParams(rules, overrideIndex);
            log.info("Step B complete — expanded {} record parameters from YAML", params.size());

            Map<String, ProductRecord> existing = loadExistingProducts(outputFile);

            List<ProductRecord> records = generateDescriptions(params, existing, outputFile);
            log.info("Step C complete — total={} cached={} generated={}",
                    records.size(), existing.size(), records.size() - existing.size());

            outputFile.getParentFile().mkdirs();
            objectMapper.writeValue(outputFile, records);
            log.info("Written {} product records to products.json", records.size());

            long durationMs = System.currentTimeMillis() - start;
            return CatalogGenerationResult.success(records.size(), durationMs, outputFile.getPath());

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - start;
            log.error("Catalog generation failed: {}", e.getMessage(), e);
            return CatalogGenerationResult.failure(e.getMessage(), durationMs);
        }
    }

    private record RecordParams(
            String  productId,
            String  category,
            String  brandCode,
            String  brandName,
            String  modelName,
            String  variantLabel,
            double  price,
            boolean freeShipping,
            boolean warrantyIncluded,
            boolean inStock,
            double  rating
    ) {}

    private List<RecordParams> expandParams(
            ProductRulesConfig rules,
            Map<String, ProductRulesConfig.VariantOverride> overrideIndex) {

        List<RecordParams> params = new ArrayList<>();

        for (ProductRulesConfig.Product product : rules.getProducts()) {
            ProductRulesConfig.BrandEntry brand = rules.getBrands().get(product.getBrandCode());
            if (brand == null) {
                log.warn("Unknown brandCode '{}' in product '{}' — skipping",
                        product.getBrandCode(), product.getProductId());
                continue;
            }

            List<String> variants = product.getVariants();
            if (variants == null || variants.isEmpty()) {
                log.warn("Product '{}' has no variants — skipping", product.getProductId());
                continue;
            }

            for (String variantLabel : variants) {

                String overrideKey = product.getProductId() + "::" + variantLabel;
                ProductRulesConfig.VariantOverride override = overrideIndex.get(overrideKey);
                double price = (override != null) ? override.getPrice() : product.getPrice();
                if (override != null) {
                    log.debug("Adversarial override applied: product={} variant='{}' price={} ({})",
                            product.getProductId(), variantLabel, price, override.getReason());
                }

                String variantSlug = variantLabel
                        .toLowerCase()
                        .replace(" ", "-")
                        .replace("/", "-");
                String productId = product.getProductId() + "-" + variantSlug;

                params.add(new RecordParams(
                        productId,
                        product.getCategory(),
                        product.getBrandCode(),
                        brand.getName(),
                        product.getModelName(),
                        variantLabel,
                        price,
                        product.isFreeShipping(),
                        product.isWarrantyIncluded(),
                        product.isInStock(),
                        product.getRating()));
            }
        }
        return params;
    }

    private static final int DESCRIPTION_THREADS = 20;

    private List<ProductRecord> generateDescriptions(List<RecordParams> params,
                                                      Map<String, ProductRecord> existing,
                                                      File outputFile) {

        List<RecordParams> toGenerate = new ArrayList<>();
        for (RecordParams p : params) {
            if (!existing.containsKey(p.productId())) {
                toGenerate.add(p);
            }
        }

        int total = params.size();
        int cached = total - toGenerate.size();
        int needed = toGenerate.size();
        log.info("Step C — total={} cached={} to-generate={}", total, cached, needed);

        if (needed == 0) {
            log.info("All {} records already cached — skipping OpenAI entirely", total);
            List<ProductRecord> result = new ArrayList<>(total);
            for (RecordParams p : params) result.add(existing.get(p.productId()));
            return result;
        }

        int threads = Math.min(DESCRIPTION_THREADS, needed);
        AtomicInteger completed = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        log.info("Launching {} threads to generate {} descriptions", threads, needed);

        Map<String, ProductRecord> generated = new HashMap<>(needed * 2);

        try {
            List<CompletableFuture<ProductRecord>> futures = new ArrayList<>(needed);
            for (RecordParams p : toGenerate) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    String description = generateDescriptionWithRetry(
                            p.category(), p.brandName(), p.modelName(),
                            p.variantLabel(), p.freeShipping(), p.warrantyIncluded());

                    int done = completed.incrementAndGet();
                    if (done % 20 == 0 || done == needed) {
                        log.info("  generated {}/{} new descriptions", done, needed);
                    }

                    return new ProductRecord(
                            p.productId(), p.category(), p.brandCode(), p.brandName(),
                            p.modelName(), p.variantLabel(), p.price(),
                            p.freeShipping(), p.warrantyIncluded(), p.inStock(),
                            p.rating(), description);
                }, executor));
            }

            for (CompletableFuture<ProductRecord> f : futures) {
                try {
                    ProductRecord r = f.get();
                    generated.put(r.productId(), r);

                    // Checkpoint: flush every successfully generated record to disk immediately.

                    // and will be skipped on the next run via the productId idempotency check.
                    checkpoint(outputFile, params, existing, generated);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Description generation interrupted", e);
                } catch (Exception e) {

                    throw new RuntimeException(e.getCause() != null ? e.getCause().getMessage() : e.getMessage(), e);
                }
            }

        } finally {
            executor.shutdownNow();
        }

        // ── Reconstruct in YAML order: cached || newly generated ────────────
        List<ProductRecord> result = new ArrayList<>(total);
        for (RecordParams p : params) {
            result.add(existing.containsKey(p.productId())
                    ? existing.get(p.productId())
                    : generated.get(p.productId()));
        }
        return result;
    }

    private void checkpoint(File outputFile,
                             List<RecordParams> params,
                             Map<String, ProductRecord> existing,
                             Map<String, ProductRecord> generated) {
        List<ProductRecord> snapshot = new ArrayList<>(existing.size() + generated.size());
        for (RecordParams p : params) {
            if (existing.containsKey(p.productId())) {
                snapshot.add(existing.get(p.productId()));
            } else if (generated.containsKey(p.productId())) {
                snapshot.add(generated.get(p.productId()));
            }

        }
        try {
            outputFile.getParentFile().mkdirs();
            objectMapper.writeValue(outputFile, snapshot);
            log.debug("Checkpoint written — {}/{} records persisted",
                    snapshot.size(), params.size());
        } catch (Exception e) {
            log.warn("Checkpoint write failed ({} records) — will retry on next record: {}",
                    snapshot.size(), e.getMessage());
        }
    }

    private Map<String, ProductRecord> loadExistingProducts(File outputFile) {
        if (!outputFile.exists() || outputFile.length() == 0) {
            log.info("products.json not found or empty — starting from scratch");
            return new HashMap<>();
        }
        try {
            List<ProductRecord> list = objectMapper.readValue(
                    outputFile, new TypeReference<List<ProductRecord>>() {});
            Map<String, ProductRecord> index = new HashMap<>(list.size() * 2);
            for (ProductRecord r : list) {
                index.put(r.productId(), r);
            }
            log.info("Loaded {} existing product records from {} — will reuse these",
                    index.size(), outputFile.getPath());
            return index;
        } catch (Exception e) {
            log.warn("Could not parse existing products.json ({}) — regenerating all records",
                    e.getMessage());
            return new HashMap<>();
        }
    }

    private String generateDescriptionWithRetry(String category, String brandName,
                                                 String modelName, String variantLabel,
                                                 boolean freeShipping, boolean warrantyIncluded) {
        String userPrompt = buildDescriptionPrompt(
                category, brandName, modelName, variantLabel, freeShipping, warrantyIncluded);
        return chatModel.chat(ChatRequest.builder()
                .messages(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(userPrompt))
                .maxOutputTokens(DESCRIPTION_MAX_TOKENS)
                .build()).aiMessage().text();
    }

    private static String buildDescriptionPrompt(String category, String brandName,
                                                  String modelName, String variantLabel,
                                                  boolean freeShipping, boolean warrantyIncluded) {
        return "Write exactly 2-3 sentences describing the experience of owning \n" +
                "and using this specific product variant.\n" +
                "\n" +
                "Hard rules — violating any of these makes the description unusable:\n" +
                "- Never start with \"The [color] finish\" or \"The [color] variant\"\n" +
                "- Never use these words: seamlessly, effortlessly, sophisticated, \n" +
                "  sleek, exudes, elevates, enhances, ensures, ideal, perfect, \n" +
                "  great, innovative, cutting-edge\n" +
                "- Never mention price, shipping, or warranty\n" +
                "- Never repeat the product name or model number\n" +
                "\n" +
                "What to write instead:\n" +
                "- Describe a specific real-world moment or situation where this \n" +
                "  product gets used\n" +
                "- If this is a color variant, describe the kind of person who \n" +
                "  chooses this color and why — their context, their setting, \n" +
                "  their reason for picking this over the other colors\n" +
                "- Focus on what the product actually does in someone's hands, \n" +
                "  not what it looks like sitting on a shelf\n" +
                "\n" +
                "Category: " + category + "\n" +
                "Brand: " + brandName + "\n" +
                "Model: " + modelName + "\n" +
                "Variant: " + variantLabel + "\n" +
                "\n" +
                "Return only the description. No preamble. No quotes. \n" +
                "No bullet points.";
    }

    @SuppressWarnings("unchecked")
    private ProductRulesConfig loadRules() {
        try (InputStream stream = new ClassPathResource("curated-product-rules.yaml").getInputStream()) {
            Map<String, Object> raw = new Yaml().load(stream);
            return objectMapper.convertValue(raw, ProductRulesConfig.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load curated-product-rules.yaml: " + e.getMessage(), e);
        }
    }

    private Map<String, ProductRulesConfig.VariantOverride> buildOverrideIndex(
            ProductRulesConfig rules) {
        Map<String, ProductRulesConfig.VariantOverride> idx = new HashMap<>();
        if (rules.getVariantOverrides() != null) {
            for (ProductRulesConfig.VariantOverride o : rules.getVariantOverrides()) {
                idx.put(o.getProductId() + "::" + o.getVariantLabel(), o);
            }
        }
        log.info("Built override index with {} entries", idx.size());
        return idx;
    }
}
