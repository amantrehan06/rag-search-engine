package com.productsearch.controller;

import com.productsearch.model.ProductSearchRequest;
import com.productsearch.model.ProductSearchResponse;
import com.productsearch.model.ProductSearchSteps;
import com.productsearch.pipeline.SearchPipeline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/product-search")
@CrossOrigin(origins = "*")
public class ProductSearchController {

    @Autowired
    private SearchPipeline searchPipeline;

    @PostMapping("/chat")
    public ResponseEntity<ProductSearchResponse> chatWithProductAssistant(
            @RequestBody ProductSearchRequest request) {
        try {
            ProductSearchResponse response = searchPipeline.processProductSearch(request);

            if (response.isSuccess()) {
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.badRequest().body(response);
            }

        } catch (Exception e) {
            log.error("Error processing product search request: {}", e.getMessage(), e);
            ProductSearchResponse errorResponse = ProductSearchResponse.builder()
                    .success(false)
                    .message("Error processing product search request: " + e.getMessage())
                    .steps(ProductSearchSteps.builder().build())
                    .build();
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", "product-search-rag");
        health.put("timestamp", java.time.LocalDateTime.now().toString());
        return ResponseEntity.ok(health);
    }
}
