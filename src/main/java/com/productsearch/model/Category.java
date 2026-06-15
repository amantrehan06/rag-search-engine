package com.productsearch.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Category {
    private String id;          // "audio" / "footwear" / "furniture"
    private String name;        // "Audio" / "Footwear" / "Furniture"
    private String description; // 2-3 sentences used as the embedding text
}
