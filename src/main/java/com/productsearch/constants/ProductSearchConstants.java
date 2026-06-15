package com.productsearch.constants;

public final class ProductSearchConstants {

    private ProductSearchConstants() {}

    public static final String CATEGORY_FIELD = "category";
    public static final String PRICE_FIELD = "price";
    public static final String BRAND_CODE_FIELD = "brand_code";
    public static final String WARRANTY_INCLUDED_FIELD = "warranty_included";
    public static final String FREE_SHIPPING_FIELD = "free_shipping";

    public static final String ID_KEY = "id";
    public static final String NAME_KEY = "name";
    public static final String CATEGORY_KEY = "category";
    public static final String BRAND_NAME_KEY = "brand_name";
    public static final String BRAND_CODE_KEY = "brand_code";
    public static final String PRICE_KEY = "price";
    public static final String DESCRIPTION_KEY = "description";
    public static final String VARIANT_LABEL_KEY = "variant_label";
    public static final String WARRANTY_INCLUDED_KEY = "warranty_included";
    public static final String FREE_SHIPPING_KEY = "free_shipping";

    public static final String EQUALS_OPERATOR = "$eq";
    public static final String IN_OPERATOR = "$in";
    public static final String GREATER_THAN_EQUAL_OPERATOR = "$gte";
    public static final String LESS_THAN_EQUAL_OPERATOR = "$lte";

    public static final Double DEFAULT_MIN_PRICE = 0.0;
    public static final Double DEFAULT_MAX_PRICE = 50000.0;
    public static final Double DEFAULT_CONFIDENCE = 0.9;

    public static final String HYBRID_SEARCH_METHOD = "Hybrid search (metadata + vector)";
    public static final String NO_SEARCH_METHOD = "No search - missing category information";
    public static final String DIRECT_CATEGORY_SPECIFICATION_METHOD = "Direct category specification";
    public static final String NO_SEARCH_PERFORMED_METHOD = "No search performed";

    public static final String NO_SEARCH_QUERY = "No search performed - missing category";

    public static final String PRODUCT_SEARCH_SUCCESS_MESSAGE = "Product search completed successfully";
    public static final String NO_PRODUCTS_MESSAGE = "No products found";
    public static final String FOLLOW_UP_QUESTIONS_MESSAGE = "Follow-up questions generated";
}
