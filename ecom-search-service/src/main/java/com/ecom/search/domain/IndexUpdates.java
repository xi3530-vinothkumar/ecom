package com.ecom.search.domain;

import com.ecom.common.events.payload.ProductChangedPayload;
import com.ecom.common.events.payload.StockLevelChangedPayload;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Painless scripts + their parameters for versioned partial updates. Every script is a no-op unless
 * the incoming version is strictly greater than the version stored for that field group, so
 * redelivered, duplicated or reordered events can never regress a document.
 */
public final class IndexUpdates {

    /** Fields owned by the catalog stream (removed on tombstoning). */
    public static final List<String> CATALOG_FIELDS = List.of(
            "name", "description", "categoryId", "brand", "variantName", "price", "currency", "active");

    /** ctx.op value for "do nothing": the update API expects 'none', update_by_query 'noop'. */
    public static final String NOOP_UPDATE = "none";
    public static final String NOOP_BY_QUERY = "noop";

    public static final String CATALOG_UPSERT = """
            def cur = ctx._source.catalogVersion;
            if (cur != null && params.version <= cur) {
              ctx.op = params.noop;
            } else {
              ctx._source.sku = params.sku;
              ctx._source.productId = params.productId;
              ctx._source.name = params.name;
              ctx._source.description = params.description;
              ctx._source.categoryId = params.categoryId;
              ctx._source.brand = params.brand;
              ctx._source.variantName = params.variantName;
              ctx._source.price = params.price;
              ctx._source.currency = params.currency;
              ctx._source.active = params.active;
              ctx._source.catalogVersion = params.version;
              ctx._source.catalogDeleted = false;
              if (ctx._source.inStock == null) {
                ctx._source.inStock = false;
                ctx._source.availableQty = 0;
              }
              ctx._source.lastUpdated = params.now;
            }
            """;

    public static final String CATALOG_TOMBSTONE = """
            def cur = ctx._source.catalogVersion;
            if (cur != null && params.version <= cur) {
              ctx.op = params.noop;
            } else {
              for (def f : params.fields) {
                ctx._source.remove(f);
              }
              if (params.sku != null) {
                ctx._source.sku = params.sku;
              }
              ctx._source.productId = params.productId;
              ctx._source.catalogVersion = params.version;
              ctx._source.catalogDeleted = true;
              ctx._source.lastUpdated = params.now;
            }
            """;

    public static final String STOCK_UPSERT = """
            def cur = ctx._source.stockVersion;
            if (cur != null && params.version <= cur) {
              ctx.op = params.noop;
            } else {
              ctx._source.sku = params.sku;
              ctx._source.inStock = params.inStock;
              ctx._source.availableQty = params.availableQty;
              ctx._source.stockVersion = params.version;
              ctx._source.lastUpdated = params.now;
            }
            """;

    public static Map<String, Object> catalogParams(ProductChangedPayload p, ProductChangedPayload.Variant v, Instant now) {
        Map<String, Object> params = new HashMap<>();
        params.put("noop", NOOP_UPDATE);
        params.put("sku", v.sku());
        params.put("productId", p.productId());
        params.put("name", p.name());
        params.put("description", p.description());
        params.put("categoryId", p.categoryId());
        params.put("brand", p.brand());
        params.put("variantName", v.variantName());
        // kept as a decimal string in _source (exact); the scaled_float mapping coerces it for sorting/ranges
        params.put("price", v.price() == null ? null : v.price().toBigDecimal().toPlainString());
        params.put("currency", v.price() == null ? null : v.price().currencyCode());
        params.put("active", p.active());
        params.put("version", p.version());
        params.put("now", now.toEpochMilli());
        return params;
    }

    public static Map<String, Object> tombstoneParams(String productId, String sku, long version, String noop, Instant now) {
        Map<String, Object> params = new HashMap<>();
        params.put("noop", noop);
        params.put("fields", CATALOG_FIELDS);
        params.put("sku", sku);
        params.put("productId", productId);
        params.put("version", version);
        params.put("now", now.toEpochMilli());
        return params;
    }

    public static Map<String, Object> stockParams(StockLevelChangedPayload s, Instant now) {
        Map<String, Object> params = new HashMap<>();
        params.put("noop", NOOP_UPDATE);
        params.put("sku", s.sku());
        params.put("inStock", s.inStock());
        params.put("availableQty", s.availableQty());
        params.put("version", s.version());
        params.put("now", now.toEpochMilli());
        return params;
    }

    private IndexUpdates() {}
}
