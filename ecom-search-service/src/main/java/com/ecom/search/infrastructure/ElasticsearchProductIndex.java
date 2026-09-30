package com.ecom.search.infrastructure;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.search.FieldCollapse;
import co.elastic.clients.json.JsonData;
import com.ecom.platform.core.EcomException;
import com.ecom.search.domain.IndexUpdates;
import com.ecom.search.domain.SearchDocument;
import com.ecom.search.domain.SearchQuery;
import com.ecom.search.domain.SearchResult;
import com.ecom.search.domain.SearchResult.FacetBucket;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchAggregations;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.DeleteQuery;
import org.springframework.data.elasticsearch.core.query.FetchSourceFilter;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.data.elasticsearch.core.query.types.ConflictsType;
import org.springframework.stereotype.Component;

/** All Elasticsearch access for the {@code products} index, via Spring Data Elasticsearch. */
@Component
public class ElasticsearchProductIndex implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchProductIndex.class);
    private static final String MAPPING = "elasticsearch/products-index.json";
    private static final String PAINLESS = "painless";
    private static final int RETRY_ON_CONFLICT = 5;
    private static final int FACET_SIZE = 50;
    static final List<String> FACETS = List.of("categoryId", "brand", "inStock");

    private final ElasticsearchOperations operations;
    private final IndexCoordinates index;

    public ElasticsearchProductIndex(ElasticsearchOperations operations, SearchProperties props) {
        this.operations = operations;
        this.index = IndexCoordinates.of(props.index());
    }

    /** Runs during context refresh, i.e. before Kafka listener containers start consuming. */
    @Override
    public void afterPropertiesSet() throws InterruptedException {
        int attempts = 10;
        for (int i = 1; ; i++) {
            try {
                ensureIndex();
                return;
            } catch (RuntimeException e) {
                if (i == attempts) {
                    throw new IllegalStateException("Cannot initialize Elasticsearch index " + index.getIndexName(), e);
                }
                log.warn("Elasticsearch not ready (attempt {}/{}): {}", i, attempts, e.toString());
                Thread.sleep(3_000);
            }
        }
    }

    void ensureIndex() {
        IndexOperations indexOps = operations.indexOps(index);
        if (indexOps.exists()) {
            return;
        }
        Document definition = readDefinition();
        try {
            indexOps.create(Document.from(nested(definition, "settings")), Document.from(nested(definition, "mappings")));
            log.info("Created Elasticsearch index {}", index.getIndexName());
        } catch (DataAccessException e) {
            // another replica won the race
            if (!String.valueOf(e.getMessage()).contains("resource_already_exists_exception")) {
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
            operations.indexOps(index).refresh();
            List<FieldValue> keepValues = keep.stream().map(FieldValue::of).toList();
            var query = NativeQuery.builder()
                    .withQuery(q -> q.bool(b -> {
                        b.filter(f -> f.term(t -> t.field("productId").value(FieldValue.of(productId))));
                        b.filter(f -> f.exists(e -> e.field("name")));
                        b.filter(f -> f.range(r -> r.untyped(ur -> ur.field("catalogVersion").lt(JsonData.of(version)))));
                        if (!keepValues.isEmpty()) {
                            b.mustNot(m -> m.terms(t -> t.field("sku").terms(v -> v.value(keepValues))));
                        }
                        return b;
                    }))
                    .build();
            var update = UpdateQuery.builder(query)
                    .withScript(IndexUpdates.CATALOG_TOMBSTONE)
                    .withLang(PAINLESS)
                    .withParams(nonNull(params))
                    .withAbortOnVersionConflict(false)
                    .withRefreshPolicy(RefreshPolicy.IMMEDIATE)
                    .build();
            return operations.updateByQuery(update, index).getUpdated();
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    public Optional<SearchDocument> get(String sku) {
        try {
            return Optional.ofNullable(operations.get(sku, SearchDocument.class, index));
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    /** Deletes stubs and tombstones (docs without catalog data) not touched since {@code cutoff}. */
    public long purgeOrphans(Instant cutoff) {
        try {
            var query = NativeQuery.builder()
                    .withQuery(q -> q.bool(b -> b
                            .mustNot(m -> m.exists(e -> e.field("name")))
                            .filter(f -> f.range(r -> r.untyped(ur -> ur.field("lastUpdated").lt(JsonData.of(cutoff.toEpochMilli())))))))
                    .build();
            var delete = DeleteQuery.builder(query)
                    .withConflicts(ConflictsType.Proceed)
                    .withRefresh(true)
                    .build();
            return operations.delete(delete, SearchDocument.class, index).getDeleted();
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    public void refresh() {
        try {
            operations.indexOps(index).refresh();
        } catch (DataAccessException e) {
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
        var builder = NativeQuery.builder()
                .withQuery(visible(textQuery(query.text())))
                .withPageable(PageRequest.of(query.page(), query.size()))
                .withTrackTotalHits(true)
                .withSort(o -> o.score(sc -> sc.order(SortOrder.Desc)))
                .withSort(o -> o.field(f -> f.field("name.keyword").order(SortOrder.Asc)))
                .withSort(o -> o.field(f -> f.field("sku").order(SortOrder.Asc)));
        // multi-select faceting: each facet is counted under every filter except its own
        for (String facet : FACETS) {
            List<Query> others = filters.entrySet().stream()
                    .filter(e -> !e.getKey().equals(facet))
                    .map(Map.Entry::getValue)
                    .toList();
            builder.withAggregation(facet, Aggregation.of(a -> a
                    .filter(f -> f.bool(b -> b.filter(others)))
                    .aggregations("values", sub -> sub.terms(t -> t.field(facet).size(FACET_SIZE)))));
        }
        if (!filters.isEmpty()) {
            builder.withFilter(p -> p.bool(b -> b.filter(new ArrayList<>(filters.values()))));
        }
        try {
            SearchHits<SearchDocument> hits = operations.search(builder.build(), SearchDocument.class, index);
            List<SearchDocument> items = hits.getSearchHits().stream().map(SearchHit::getContent).toList();
            var aggregations = (ElasticsearchAggregations) hits.getAggregations();
            Map<String, List<FacetBucket>> facets = new LinkedHashMap<>();
            for (String facet : FACETS) {
                var aggregation = aggregations == null ? null : aggregations.get(facet);
                facets.put(facet, buckets(aggregation == null ? null : aggregation.aggregation().getAggregate()));
            }
            return new SearchResult(items, hits.getTotalHits(), query.page(), query.size(), facets);
        } catch (DataAccessException e) {
            throw EcomException.unavailable("Search failed", e);
        }
    }

    /** Distinct product names matching {@code prefix} as-you-type, best first. */
    public List<String> suggest(String prefix, int limit) {
        try {
            var query = NativeQuery.builder()
                    .withQuery(visible(Query.of(q -> q.match(m -> m.field("name.autocomplete")
                            .query(FieldValue.of(prefix))
                            .operator(Operator.And)))))
                    .withMaxResults(limit)
                    .withFieldCollapse(FieldCollapse.of(c -> c.field("productId")))
                    .withSourceFilter(new FetchSourceFilter(true, new String[] {"name"}, null))
                    .build();
            LinkedHashSet<String> names = new LinkedHashSet<>();
            operations.search(query, SearchDocument.class, index).forEach(h -> {
                if (h.getContent() != null && h.getContent().name() != null) {
                    names.add(h.getContent().name());
                }
            });
            return names.stream().limit(limit).toList();
        } catch (DataAccessException e) {
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
        var update = UpdateQuery.builder(sku)
                .withScript(source)
                .withLang(PAINLESS)
                .withParams(nonNull(params))
                .withScriptedUpsert(true)
                .withUpsert(Document.create())
                .withRetryOnConflict(RETRY_ON_CONFLICT)
                .build();
        try {
            operations.update(update, index);
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    /** Null params are left out, so scripts see them as absent rather than JSON null. */
    private static Map<String, Object> nonNull(Map<String, Object> params) {
        Map<String, Object> copy = new LinkedHashMap<>();
        params.forEach((k, v) -> {
            if (v != null) {
                copy.put(k, v);
            }
        });
        return copy;
    }

    private static Document readDefinition() {
        try (InputStream in = new ClassPathResource(MAPPING).getInputStream()) {
            return Document.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + MAPPING, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Document definition, String key) {
        return (Map<String, Object>) definition.get(key);
    }

    private static EcomException unavailable(DataAccessException e) {
        return EcomException.unavailable("Elasticsearch unavailable", e);
    }
}
