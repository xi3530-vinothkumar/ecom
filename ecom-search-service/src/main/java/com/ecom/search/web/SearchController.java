package com.ecom.search.web;

import com.ecom.search.application.SearchService;
import com.ecom.search.domain.SearchDocument;
import com.ecom.search.domain.SearchQuery;
import com.ecom.search.domain.SearchResult;
import com.ecom.search.domain.SearchResult.FacetBucket;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchController {

    private final SearchService search;

    public SearchController(SearchService search) {
        this.search = search;
    }

    @GetMapping("/search")
    public SearchResponse search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) Boolean inStock,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        SearchResult result = search.search(SearchQuery.of(q, categoryId, brand, inStock, page, size));
        return new SearchResponse(
                result.items().stream().map(SearchController::item).toList(),
                result.page(),
                result.size(),
                result.total(),
                result.facets());
    }

    @GetMapping("/search/suggest")
    public SuggestResponse suggest(@RequestParam(required = false) String q) {
        return new SuggestResponse(search.suggest(q));
    }

    static SearchItem item(SearchDocument d) {
        return new SearchItem(
                d.sku(),
                d.productId(),
                d.name(),
                d.description(),
                d.variantName(),
                d.brand(),
                d.categoryId(),
                d.price() == null ? null : new Price(plain(d.price()), d.currency()),
                Boolean.TRUE.equals(d.inStock()),
                d.availableQty() == null ? 0 : d.availableQty());
    }

    static String plain(BigDecimal amount) {
        BigDecimal n = amount.stripTrailingZeros();
        return (n.scale() < 2 ? n.setScale(2) : n).toPlainString();
    }

    public record Price(String amount, String currency) {}

    public record SearchItem(
            String sku,
            String productId,
            String name,
            String description,
            String variantName,
            String brand,
            String categoryId,
            Price price,
            boolean inStock,
            int availableQty) {}

    public record SearchResponse(
            List<SearchItem> items, int page, int size, long total, Map<String, List<FacetBucket>> facets) {}

    public record SuggestResponse(List<String> suggestions) {}
}
