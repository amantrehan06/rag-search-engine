package com.productsearch.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.productsearch.model.ProductRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class CatalogMetadataProvider {

    public record Snapshot(Set<String> categoryIds, Set<String> brandCodes) {
        public boolean isEmpty() { return categoryIds.isEmpty() && brandCodes.isEmpty(); }
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<Snapshot> snapshotRef = new AtomicReference<>();

    public Snapshot get() {
        Snapshot s = snapshotRef.get();
        if (s != null) return s;
        synchronized (this) {
            s = snapshotRef.get();
            if (s == null) snapshotRef.set(s = load());
            return s;
        }
    }

    public void invalidate() { snapshotRef.set(null); }

    private Snapshot load() {
        try {
            List<ProductRecord> records = readProductsJson();
            if (records.isEmpty()) {
                log.warn("CatalogMetadataProvider: products.json missing or empty");
                return new Snapshot(Set.of(), Set.of());
            }
            Set<String> categories = new LinkedHashSet<>();
            Set<String> brandCodes = new LinkedHashSet<>();
            for (ProductRecord r : records) {
                if (r.category()  != null && !r.category().isBlank())  categories.add(r.category());
                if (r.brandCode() != null && !r.brandCode().isBlank()) brandCodes.add(r.brandCode());
            }
            log.info("CatalogMetadataProvider — {} categories, {} brands", categories.size(), brandCodes.size());
            return new Snapshot(
                    Collections.unmodifiableSet(categories),
                    Collections.unmodifiableSet(brandCodes));
        } catch (Exception e) {
            log.error("CatalogMetadataProvider load failed: {}", e.getMessage(), e);
            return new Snapshot(Set.of(), Set.of());
        }
    }

    private List<ProductRecord> readProductsJson() throws Exception {
        TypeReference<List<ProductRecord>> typeRef = new TypeReference<>() {};
        File srcFile = new File("src/main/resources/products.json");
        if (srcFile.exists() && srcFile.length() > 0) {
            try (InputStream is = new FileInputStream(srcFile)) { return objectMapper.readValue(is, typeRef); }
        }
        ClassPathResource cpr = new ClassPathResource("products.json");
        if (cpr.exists()) {
            try (InputStream is = cpr.getInputStream()) { return objectMapper.readValue(is, typeRef); }
        }
        return List.of();
    }
}
