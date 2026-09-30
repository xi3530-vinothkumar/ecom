package com.ecom.search.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecom.common.events.EventTopics;
import com.ecom.search.SearchIntegrationTest;
import com.ecom.search.domain.SearchDocument;
import com.ecom.search.domain.SearchQuery;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class IndexingIT extends SearchIntegrationTest {

    private SearchDocument doc(String sku) {
        return index.get(sku).orElseThrow(() -> new AssertionError("no doc for " + sku));
    }

    private long visibleInCategory(String categoryId) {
        return index.search(SearchQuery.of(null, categoryId, null, null, 0, 50)).total();
    }

    @Test
    void stockBeforeProductProducesCompleteDocument() {
        String sku = sku();
        String productId = id();
        String category = id();

        stock(sku, 5, 1);
        awaitIndexed();
        SearchDocument stub = doc(sku);
        assertThat(stub.name()).isNull();
        assertThat(stub.availableQty()).isEqualTo(5);
        assertThat(visibleInCategory(category)).isZero();

        productChanged(product(productId, "Race Tee", 1, category, "Acme", sku), true);
        awaitIndexed();

        SearchDocument full = doc(sku);
        assertThat(full.name()).isEqualTo("Race Tee");
        assertThat(full.productId()).isEqualTo(productId);
        assertThat(full.price()).isEqualByComparingTo(new BigDecimal("19.99"));
        assertThat(full.currency()).isEqualTo("EUR");
        assertThat(full.inStock()).isTrue();
        assertThat(full.availableQty()).isEqualTo(5);
        assertThat(full.catalogVersion()).isEqualTo(1);
        assertThat(full.stockVersion()).isEqualTo(1);
        assertThat(visibleInCategory(category)).isEqualTo(1);
    }

    @Test
    void productBeforeStockDefaultsToOutOfStockUntilStockArrives() {
        String sku = sku();
        productChanged(product(id(), "Early Tee", 1, id(), "Acme", sku), true);
        awaitIndexed();
        assertThat(doc(sku).inStock()).isFalse();

        stock(sku, 2, 4);
        awaitIndexed();
        assertThat(doc(sku).inStock()).isTrue();
        assertThat(doc(sku).name()).isEqualTo("Early Tee");
    }

    @Test
    void staleCatalogAndStockEventsAreIgnored() {
        String sku = sku();
        String productId = id();
        String category = id();

        productChanged(product(productId, "Version One", 1, category, "Acme", sku), true);
        productChanged(product(productId, "Version Three", 3, category, "Acme", sku), false);
        productChanged(product(productId, "Version Two", 2, category, "Acme", sku), false);
        stock(sku, 9, 5);
        stock(sku, 1, 3);
        awaitIndexed();

        SearchDocument d = doc(sku);
        assertThat(d.name()).isEqualTo("Version Three");
        assertThat(d.catalogVersion()).isEqualTo(3);
        assertThat(d.availableQty()).isEqualTo(9);
        assertThat(d.stockVersion()).isEqualTo(5);
    }

    @Test
    void redeliveryIsIdempotent() {
        String sku = sku();
        String productId = id();
        var created = send(EventTopics.PRODUCT_CREATED, productId, product(productId, "Once", 1, id(), "Acme", sku));
        var stockEnv = send(EventTopics.STOCK_LEVEL_CHANGED, sku,
                new com.ecom.common.events.payload.StockLevelChangedPayload(sku, 4, true, 2));
        awaitIndexed();
        SearchDocument first = doc(sku);

        resend(EventTopics.PRODUCT_CREATED, productId, created);
        resend(EventTopics.STOCK_LEVEL_CHANGED, sku, stockEnv);
        awaitIndexed();

        SearchDocument again = doc(sku);
        assertThat(again).isEqualTo(first);

        productChanged(product(productId, "Renamed", 2, first.categoryId(), "Acme", sku), false);
        resend(EventTopics.PRODUCT_CREATED, productId, created);
        awaitIndexed();
        assertThat(doc(sku).name()).isEqualTo("Renamed");
    }

    @Test
    void deleteHidesAllSkusAndCannotBeUndoneByLateEvents() {
        String a = sku();
        String b = sku();
        String productId = id();
        String category = id();
        productChanged(product(productId, "Doomed", 1, category, "Acme", a, b), true);
        awaitIndexed();
        assertThat(visibleInCategory(category)).isEqualTo(2);

        productDeleted(productId, 3, a, b);
        awaitIndexed();
        assertThat(visibleInCategory(category)).isZero();

        // reordered older update and later stock changes must not resurrect it
        productChanged(product(productId, "Doomed v2", 2, category, "Acme", a, b), false);
        stock(a, 10, 7);
        awaitIndexed();
        assertThat(visibleInCategory(category)).isZero();
        assertThat(doc(a).name()).isNull();
        assertThat(doc(a).catalogDeleted()).isTrue();
        assertThat(doc(a).catalogVersion()).isEqualTo(3);
    }

    @Test
    void deleteArrivingBeforeCreateWins() {
        String sku = sku();
        String productId = id();
        String category = id();
        productDeleted(productId, 2, sku);
        awaitIndexed();
        productChanged(product(productId, "Too Late", 1, category, "Acme", sku), true);
        awaitIndexed();
        assertThat(visibleInCategory(category)).isZero();
    }

    @Test
    void shrinkingVariantListRemovesDroppedSkus() {
        String keep = sku();
        String drop = sku();
        String productId = id();
        String category = id();
        productChanged(product(productId, "Shrinking", 1, category, "Acme", keep, drop), true);
        productChanged(product(productId, "Shrinking", 2, category, "Acme", keep), false);
        awaitIndexed();

        var result = index.search(SearchQuery.of(null, category, null, null, 0, 10));
        assertThat(result.items()).extracting(SearchDocument::sku).containsExactly(keep);
        assertThat(doc(drop).name()).isNull();
        assertThat(doc(drop).catalogVersion()).isEqualTo(2);
    }

    @Test
    void purgeRemovesOnlyOrphans() {
        String orphan = sku();
        String live = sku();
        stock(orphan, 1, 1);
        productChanged(product(id(), "Keeper", 1, id(), "Acme", live), true);
        stock(live, 1, 1);
        awaitIndexed();

        long deleted = index.purgeOrphans(Instant.now().plusSeconds(60));

        assertThat(deleted).isPositive();
        assertThat(index.get(orphan)).isEmpty();
        assertThat(index.get(live)).isPresent();
    }
}
