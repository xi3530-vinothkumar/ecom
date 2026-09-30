package com.ecom.search.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ecom.common.events.Money;
import com.ecom.common.events.payload.ProductChangedPayload;
import com.ecom.common.events.payload.ProductDeletedPayload;
import com.ecom.common.events.payload.StockLevelChangedPayload;
import com.ecom.search.infrastructure.ElasticsearchProductIndex;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IndexingServiceTest {

    private final ElasticsearchProductIndex index = mock(ElasticsearchProductIndex.class);
    private final IndexingService service =
            new IndexingService(index, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

    private static ProductChangedPayload.Variant variant(String sku) {
        return new ProductChangedPayload.Variant(sku, "M", Money.of("1", "EUR"));
    }

    @Test
    void upsertsEveryVariantThenTombstonesTheRest() {
        service.onProductChanged(new ProductChangedPayload("p1", "Tee", null, "c", null, true, 3,
                List.of(variant("A"), variant("B"))));

        verify(index).upsertCatalog(eq("A"), anyMap());
        verify(index).upsertCatalog(eq("B"), anyMap());
        verify(index).tombstoneOtherSkus(eq("p1"), eq(Set.of("A", "B")), eq(3L), anyMap());
    }

    @Test
    void deleteTombstonesEachListedSku() {
        service.onProductDeleted(new ProductDeletedPayload("p1", List.of("A", "B"), 5));
        verify(index).tombstone(eq("A"), anyMap());
        verify(index).tombstone(eq("B"), anyMap());
    }

    @Test
    void malformedPayloadsAreNotRetryable() {
        assertThatThrownBy(() -> service.onStockChanged(new StockLevelChangedPayload(" ", 1, true, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.onProductChanged(
                        new ProductChangedPayload(null, "x", null, null, null, true, 1, List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.onProductChanged(
                        new ProductChangedPayload("p", "x", null, null, null, true, 1, List.of(variant(null)))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(index);
    }
}
