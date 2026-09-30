package com.ecom.search.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecom.common.events.Money;
import com.ecom.common.events.payload.ProductChangedPayload;
import com.ecom.common.events.payload.StockLevelChangedPayload;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndexUpdatesTest {

    private final Instant now = Instant.ofEpochMilli(1_700_000_000_000L);

    @Test
    void catalogParamsFlattenProductAndVariantWithExactPrice() {
        var v = new ProductChangedPayload.Variant("SKU-1", "Large", Money.of("19.9900", "EUR"));
        var p = new ProductChangedPayload("p1", "Tee", "Cotton", "c1", "Acme", true, 7, List.of(v));

        var params = IndexUpdates.catalogParams(p, v, now);

        assertThat(params).containsEntry("sku", "SKU-1").containsEntry("productId", "p1").containsEntry("name", "Tee")
                .containsEntry("variantName", "Large").containsEntry("price", "19.99").containsEntry("currency", "EUR")
                .containsEntry("active", true).containsEntry("version", 7L).containsEntry("now", now.toEpochMilli())
                .containsEntry("noop", IndexUpdates.NOOP_UPDATE);
    }

    @Test
    void stockParamsCarryStockVersion() {
        var params = IndexUpdates.stockParams(new StockLevelChangedPayload("SKU-1", 3, true, 12), now);
        assertThat(params).containsEntry("availableQty", 3).containsEntry("inStock", true).containsEntry("version", 12L);
    }

    @Test
    void tombstoneRemovesAllCatalogFieldsButKeepsIdentity() {
        var params = IndexUpdates.tombstoneParams("p1", null, 4, IndexUpdates.NOOP_BY_QUERY, now);
        assertThat(params.get("fields")).isEqualTo(IndexUpdates.CATALOG_FIELDS);
        assertThat(IndexUpdates.CATALOG_FIELDS).contains("name", "active").doesNotContain("productId", "sku");
        assertThat(params).containsEntry("noop", "noop").containsEntry("sku", null);
    }

    @Test
    void everyScriptIsVersionGuarded() {
        assertThat(IndexUpdates.CATALOG_UPSERT).contains("params.version <= cur", "ctx._source.catalogVersion");
        assertThat(IndexUpdates.CATALOG_TOMBSTONE).contains("params.version <= cur", "ctx._source.catalogVersion");
        assertThat(IndexUpdates.STOCK_UPSERT).contains("params.version <= cur", "ctx._source.stockVersion");
    }
}
