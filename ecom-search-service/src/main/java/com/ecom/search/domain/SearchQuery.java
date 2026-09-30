package com.ecom.search.domain;

import com.ecom.platform.core.EcomException;

/** Validated, normalized search request. */
public record SearchQuery(String text, String categoryId, String brand, Boolean inStock, int page, int size) {

    public static final int MAX_SIZE = 100;
    public static final int MAX_WINDOW = 10_000; // Elasticsearch index.max_result_window default
    public static final int MAX_TEXT = 200;

    public static SearchQuery of(String q, String categoryId, String brand, Boolean inStock, Integer page, Integer size) {
        int p = page == null ? 0 : page;
        int s = size == null ? 20 : size;
        if (p < 0) {
            throw EcomException.validation("page must be >= 0");
        }
        if (s < 1 || s > MAX_SIZE) {
            throw EcomException.validation("size must be between 1 and " + MAX_SIZE);
        }
        if ((long) (p + 1) * s > MAX_WINDOW) {
            throw EcomException.validation("page * size must not exceed " + MAX_WINDOW + "; refine the query instead");
        }
        String text = blankToNull(q);
        if (text != null && text.length() > MAX_TEXT) {
            throw EcomException.validation("q must be at most " + MAX_TEXT + " characters");
        }
        return new SearchQuery(text, blankToNull(categoryId), blankToNull(brand), inStock, p, s);
    }

    public int from() {
        return page * size;
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
