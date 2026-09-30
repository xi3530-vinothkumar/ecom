package com.ecom.search.application;

import com.ecom.platform.core.EcomException;
import com.ecom.search.domain.SearchQuery;
import com.ecom.search.domain.SearchResult;
import com.ecom.search.infrastructure.ElasticsearchProductIndex;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SearchService {

    public static final int SUGGEST_LIMIT = 10;
    public static final int SUGGEST_MAX_PREFIX = 100;

    private final ElasticsearchProductIndex index;

    public SearchService(ElasticsearchProductIndex index) {
        this.index = index;
    }

    public SearchResult search(SearchQuery query) {
        return index.search(query);
    }

    public List<String> suggest(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return List.of();
        }
        if (prefix.length() > SUGGEST_MAX_PREFIX) {
            throw EcomException.validation("q must be at most " + SUGGEST_MAX_PREFIX + " characters");
        }
        return index.suggest(prefix.strip(), SUGGEST_LIMIT);
    }
}
