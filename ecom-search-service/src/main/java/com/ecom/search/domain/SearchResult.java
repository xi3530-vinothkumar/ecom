package com.ecom.search.domain;

import java.util.List;
import java.util.Map;

public record SearchResult(List<SearchDocument> items, long total, int page, int size, Map<String, List<FacetBucket>> facets) {

    public record FacetBucket(String value, long count) {}
}
