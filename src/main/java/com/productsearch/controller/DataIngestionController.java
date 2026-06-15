package com.productsearch.controller;

import com.productsearch.model.CatalogGenerationResult;
import com.productsearch.model.IngestionResult;
import com.productsearch.service.CatalogGenerationService;
import com.productsearch.service.CategoryIngestionService;
import com.productsearch.service.ProductIngestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/ingestion")
@CrossOrigin(origins = "*")
public class DataIngestionController {

    private final CatalogGenerationService    catalogGenerationService;
    private final CategoryIngestionService    categoryIngestionService;
    private final ProductIngestionService     productIngestionService;
    private final com.productsearch.infra.CatalogMetadataProvider catalogMetadata;

    @Autowired
    public DataIngestionController(CatalogGenerationService catalogGenerationService,
                                   CategoryIngestionService categoryIngestionService,
                                   ProductIngestionService  productIngestionService,
                                   com.productsearch.infra.CatalogMetadataProvider catalogMetadata) {
        this.catalogGenerationService = catalogGenerationService;
        this.categoryIngestionService = categoryIngestionService;
        this.productIngestionService  = productIngestionService;
        this.catalogMetadata          = catalogMetadata;
    }

    @PostMapping("/generate-catalog")
    public ResponseEntity<CatalogGenerationResult> generateCatalog() {
        log.info("POST /api/v1/ingestion/generate-catalog — expanding YAML rules → products.json");
        CatalogGenerationResult result = catalogGenerationService.generateCatalog();
        if (result.success()) {
            catalogMetadata.invalidate(); // next intent-parse call re-reads products.json
        }
        log.info("Catalog generation complete: success={} recordsGenerated={} outputPath={} durationMs={}",
                result.success(), result.recordsGenerated(), result.outputPath(), result.durationMs());
        return result.success()
                ? ResponseEntity.ok(result)
                : ResponseEntity.internalServerError().body(result);
    }

    @PostMapping("/categories")
    public ResponseEntity<IngestionResult> ingestCategories() {
        log.info("POST /api/v1/ingestion/categories — starting category ingestion");
        IngestionResult result = categoryIngestionService.ingestCategories();
        log.info("Category ingestion complete: success={} upserted={} durationMs={}",
                result.success(), result.recordsUpserted(), result.durationMs());
        return result.success()
                ? ResponseEntity.ok(result)
                : ResponseEntity.internalServerError().body(result);
    }

    @PostMapping("/products")
    public ResponseEntity<IngestionResult> ingestProducts() {
        log.info("POST /api/v1/ingestion/products — starting product ingestion");
        IngestionResult result = productIngestionService.ingestProducts();
        log.info("Product ingestion complete: success={} upserted={} durationMs={}",
                result.success(), result.recordsUpserted(), result.durationMs());
        return result.success()
                ? ResponseEntity.ok(result)
                : ResponseEntity.internalServerError().body(result);
    }
}
