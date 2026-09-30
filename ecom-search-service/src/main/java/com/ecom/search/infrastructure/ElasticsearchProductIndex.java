package com.ecom.search.infrastructure;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.Script;
import co.elastic.clients.elasticsearch._types.ScriptLanguage;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.ecom.platform.core.EcomException;
import com.ecom.search.domain.IndexUpdates;
import com.ecom.search.domain.SearchDocument;
import com.ecom.search.domain.SearchQuery;
import com.ecom.search.domain.SearchResult;
import com.ecom.search.domain.SearchResult.FacetBucket;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** All Elasticsearch access for the {@code products} index. */
@Component
public class ElasticsearchProductIndex implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchProductIndex.class);
    private static final String MAPPING = "elasticsearch/products-index.json";
    private static final int RETRY_ON_CONFLICT = 5;
    private static final int FACET_SIZE = 50;
    static final List<String> FACETS = List.of("categoryId", "brand", "inStock");

    private final ElasticsearchClient client;
    private final String index;

    public ElasticsearchProductIndex(ElasticsearchClient client, SearchProperties props) {
        this.client = client;
        this.index = props.index();
    }

    /** Runs during context refresh, i.e. before Kafka listener containers start consuming. */
    @Override
    public void afterPropertiesSet() throws InterruptedException {
        int attempts = 10;
        for (int i = 1; ; i++) {
            try {
                ensureIndex();
                return;
            } catch (IOException | RuntimeException e) {
                if (i == attempts) {
                    throw new IllegalStateException("Cannot initialize Elasticsearch index " + index, e);
                }
                log.warn("Elasticsearch not ready (attempt {}/{}): {}", i, attempts, e.toString());
                Thread.sleep(3_000);
            }
        }
    }

    void ensureIndex() throws IOException {
        if (client.indices().exists(e -> e.index(index)).value()) {
            return;
        }
        try (InputStream in = new ClassPathResource(MAPPING).getInputStream()) {
            client.indices().create(c -> c.index(index).withJson(in));
            log.info("Created Elasticsearch index {}", index);
        } catch (ElasticsearchException e) {
            // another replica won the race
            if (!"resource_already_exists_exception".equals(e.error().type())) {
                throw e;
            }
        }
    }

    public void upsertCatalog(String sku, Map<String, Object> params) {
        scriptedUpsert(sku, IndexUpdates.CATALOG_UPSERT, params);
    }

    public void tombstone(String sku, Map<String, Object> params) {
        scriptedUpsert(sku, IndexUpdates.CATALOG_TOMBSTONE, params);
    }

    public void upsertStock(String sku, Map<String, Object> params) {
        scriptedUpsert(sku, IndexUpdates.STOCK_UPSERT, params);
    }

    /**
     * Tombstones every live doc of {@code productId} whose SKU is not in {@code keep}. Refreshes first:
     * update_by_query only sees searchable docs, and the SKUs may have been indexed milliseconds ago.
     */
    public long tombstoneOtherSkus(String productId, Collection<String> keep, long version, Map<String, Object> params) {
        try {
            client.indices().refresh(r -> r.index(index));
            List<FieldValue> keepValues = keep.stream().map(FieldValue::of).toList();
            var response = client.updateByQuery(u -> u.index(index)
                    .conflicts(Conflicts.Proceed)
                    .refresh(true)
                    .query(q -> q.bool(b -> {
                        b.filter(f -> f.term(t -> t.field("productId").value(FieldValue.of(productId))));
                        b.filter(f -> f.exists(e -> e.field("name")));
                        b.filter(f -> f.range(r -> r.untyped(ur -> ur.field("catalogVersion").lt(JsonData.of(version)))));
                        if (!keepValues.isEmpty()) {
                            b.mustNot(m -> m.terms(t -> t.field("sku").terms(v -> v.value(keepValues))));
                        }
                        return b;
                    }))
                    .script(script(IndexUpdates.CATALOG_TOMBSTONE, params)));
            return response.updated() == null ? 0 : response.updated();
        } catch (IOException e) {
            throw unavailable(e);
        }
    }

    public Optional<SearchDocument> get(String sku) {
        try {
            var response = client.get(g -> g.index(index).id(sku), SearchDocument.class);
            return response.found() ? Optional.ofNullable(response.source()) : Optional.empty();
        } catch (IOException e) {
            throw unavailable(e);
        }
    }

    /** Deletes stubs and tombstones (docs without catalog data) not touched since {@code cutoff}. */
    public long purgeOrphans(Instant cutoff) {
        try {
            var response = client.deleteByQuery(d -> d.index(index)
                    .conflicts(Conflicts.Proceed)
                    .refresh(true)
                    .query(q -> q.bool(b -> b
                            .mustNot(m -> m.exists(e -> e.field("name")))
                            .filter(f -> f.range(r -> r.untyped(ur -> ur.field("lastUpdated").lt(JsonData.of(cutoff.toEpochMilli()))))))));
            return response.deleted() == null ? 0 : response.deleted();
        } catch (IOException e) {
            throw unavailable(e);
        }
    }

    public void refresh() {
        try {
            client.indices().refresh(r -> r.index(index));
        } catch (IOException e) {
            throw unavailable(e);
        }
    }

    public SearchResult search(SearchQuery query) {
        Map<String, Query> filters = new LinkedHashMap<>();
        if (query.categoryId() != null) {
            filters.put("categoryId", term("categoryId", FieldValue.of(query.categoryId())));
        }
        if (query.brand() != null) {
            filters.put("brand", term("brand", FieldValue.of(query.brand())));
        }
        if (query.inStock() != null) {
            filters.put("inStock", term("inStock", FieldValue.of(query.inStock())));
        }
        Map<String, Aggregation> aggs = new LinkedHashMap<>();
        // multi-select faceting: each facet is counted under every filter except its own
        for (String facet : FACETS) {
            List<Query> others = filters.entrySet().stream()
                    .filter(e -> !e.getKey().equals(facet))
                    .map(Map.Entry::getValue)
                    .toList();
            aggs.put(facet, Aggregation.of(a -> a
                    .filter(f -> f.bool(b -> b.filter(others)))
                    .aggregations("values", sub -> sub.terms(t -> t.field(facet).size(FACET_SIZE)))));
        }
        try {
            SearchResponse<SearchDocument> response = client.search(s -> {
                s.index(index)
                        .query(visible(textQuery(query.text())))
                        .from(query.from())
                        .size(query.size())
                        .trackTotalHits(t -> t.enabled(true))
                        .aggregations(aggs)
                        .sort(o -> o.score(sc -> sc.order(SortOrder.Desc)))
                        .sort(o -> o.field(f -> f.field("name.keyword").order(SortOrder.Asc)))
                        .sort(o -> o.field(f -> f.field("sku").order(SortOrder.Asc)));
                if (!filters.isEmpty()) {
                    s.postFilter(p -> p.bool(b -> b.filter(new ArrayList<>(filters.values()))));
                }
                return s;
            }, SearchDocument.class);
            List<SearchDocument> items = response.hits().hits().stream().map(Hit::source).toList();
            long total = response.hits().total() == null ? items.size() : response.hits().total().value();
            Map<String, List<FacetBucket>> facets = new LinkedHashMap<>();
            for (String facet : FACETS) {
                facets.put(facet, buckets(response.aggregations().get(facet)));
            }
            return new SearchResult(items, total, query.page(), query.size(), facets);
        } catch (IOException e) {
            throw unavailable(e);
        } catch (ElasticsearchException e) {
            throw EcomException.unavailable("Search failed: " + e.error().type(), e);
        }
    }

    /** Distinct product names matching {@code prefix} as-you-type, best first. */
    public List<String> suggest(String prefix, int limit) {
        try {
            var response = client.search(s -> s.index(index)
                    .size(limit)
                    .query(visible(Query.of(q -> q.match(m -> m.field("name.autocomplete")
                            .query(FieldValue.of(prefix))
                            .operator(Operator.And)))))
                    .collapse(c -> c.field("productId"))
                    .source(src -> src.filter(f -> f.includes("name"))), SearchDocument.class);
            LinkedHashSet<String> names = new LinkedHashSet<>();
            response.hits().hits().forEach(h -> {
                if (h.source() != null && h.source().name() != null) {
                    names.add(h.source().name());
                }
            });
            return names.stream().limit(limit).toList();
        } catch (IOException e) {
            throw unavailable(e);
        }
    }

    /** Stubs/tombstones (no name) and inactive products never surface. */
    private static Query visible(Query main) {
        return Query.of(q -> q.bool(b -> b
                .must(main)
                .filter(f -> f.exists(e -> e.field("name")))
                .filter(f -> f.term(t -> t.field("active").value(FieldValue.of(true))))));
    }

    private static Query textQuery(String text) {
        if (text == null) {
            return Query.of(q -> q.matchAll(m -> m));
        }
        return Query.of(q -> q.multiMatch(m -> m
                .query(text)
                .type(TextQueryType.BestFields)
                .fields("name^3", "brand.text^2", "variantName", "description")
                .fuzziness("AUTO")));
    }

    private static Query term(String field, FieldValue value) {
        return Query.of(q -> q.term(t -> t.field(field).value(value)));
    }

    private static List<FacetBucket> buckets(Aggregate facet) {
        if (facet == null) {
            return List.of();
        }
        Aggregate values = facet.filter().aggregations().get("values");
        if (values == null) {
            return List.of();
        }
        if (values.isSterms()) {
            return values.sterms().buckets().array().stream()
                    .map(b -> new FacetBucket(b.key().stringValue(), b.docCount()))
                    .toList();
        }
        if (values.isLterms()) {
            return values.lterms().buckets().array().stream()
                    .map(b -> new FacetBucket(b.keyAsString() != null ? b.keyAsString() : String.valueOf(b.key()), b.docCount()))
                    .toList();
        }
        return List.of();
    }

    private void scriptedUpsert(String sku, String source, Map<String, Object> params) {
        Map<String, Object> emptyDoc = Map.of();
        try {
            client.update(u -> u.index(index)
                    .id(sku)
                    .script(script(source, params))
                    .scriptedUpsert(true)
                    .upsert(emptyDoc)
                    .retryOnConflict(RETRY_ON_CONFLICT), Map.class);
        } catch (IOException e) {
            throw unavailable(e);
        }
    }

    private static Script script(String source, Map<String, Object> params) {
        Map<String, JsonData> json = new LinkedHashMap<>();
        params.forEach((k, v) -> {
            if (v != null) {
                json.put(k, JsonData.of(v));
            }
        });
        return Script.of(s -> s.lang(ScriptLanguage.Painless).source(src -> src.scriptString(source)).params(json));
    }

    private static EcomException unavailable(IOException e) {
        return EcomException.unavailable("Elasticsearch unavailable", e);
    }
}
