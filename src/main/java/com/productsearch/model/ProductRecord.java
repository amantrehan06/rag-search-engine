package com.productsearch.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ProductRecord(
        @JsonProperty("productId")             String  productId,
        @JsonProperty("category")              String  category,
        @JsonProperty("brandCode")             String  brandCode,
        @JsonProperty("brandName")             String  brandName,
        @JsonProperty("modelName")             String  modelName,
        @JsonProperty("variantLabel")          String  variantLabel,
        @JsonProperty("price")                 double  price,
        @JsonProperty("freeShipping")          boolean freeShipping,
        @JsonProperty("warrantyIncluded")      boolean warrantyIncluded,
        @JsonProperty("inStock")               boolean inStock,
        @JsonProperty("rating")                double  rating,
        @JsonProperty("experienceDescription") String  experienceDescription
) {}
