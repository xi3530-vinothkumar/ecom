package com.ecom.search.application;

import com.ecom.common.events.payload.ProductChangedPayload;
import com.ecom.common.events.payload.ProductDeletedPayload;
import com.ecom.common.events.payload.StockLevelChangedPayload;
import com.ecom.search.domain.IndexUpdates;
import com.ecom.search.infrastructure.ElasticsearchProductIndex;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Applies Catalog and Inventory events to the index. No dedup store: every write is a versioned
 * scripted upsert, so redeliveries and cross-topic reordering converge to the same document.
 */
@Service
public class IndexingService {

    private static final Logger log = LoggerFactory.getLogger(IndexingService.class);

    private final ElasticsearchProductIndex index;
    private final Clock clock;

    public IndexingService(ElasticsearchProductIndex index, Clock clock) {
        this.index = index;
        this.clock = clock;
    }

    /** product-created / product-updated: full state; SKUs missing from the list are tombstoned. */
    public void onProductChanged(ProductChangedPayload p) {
        require(p.productId() != null, "productId missing");
        List<ProductChangedPayload.Variant> variants = p.variants() == null ? List.of() : p.variants();
        variants.forEach(v -> require(v.sku() != null && !v.sku().isBlank(), "variant sku missing"));
        Instant now = clock.instant();
        for (ProductChangedPayload.Variant v : variants) {
            index.upsertCatalog(v.sku(), IndexUpdates.catalogParams(p, v, now));
        }
        Set<String> keep = variants.stream().map(ProductChangedPayload.Variant::sku).collect(Collectors.toSet());
        long removed = index.tombstoneOtherSkus(p.productId(), keep, p.version(),
                IndexUpdates.tombstoneParams(p.productId(), null, p.version(), IndexUpdates.NOOP_BY_QUERY, now));
        log.debug("Indexed product {} v{} ({} SKUs, {} removed)", p.productId(), p.version(), keep.size(), removed);
    }

    /**
     * Keeps a version tombstone instead of hard-deleting, so an older product-updated arriving later
     * (different topic, no cross-topic ordering) cannot resurrect the product. Purged by the nightly job.
     */
    public void onProductDeleted(ProductDeletedPayload d) {
        require(d.productId() != null, "productId missing");
        Instant now = clock.instant();
        for (String sku : d.skus() == null ? List.<String>of() : d.skus()) {
            index.tombstone(sku, IndexUpdates.tombstoneParams(d.productId(), sku, d.version(), IndexUpdates.NOOP_UPDATE, now));
        }
        log.debug("Removed product {} v{}", d.productId(), d.version());
    }

    /** May arrive before the product exists: creates a stub that queries filter out. */
    public void onStockChanged(StockLevelChangedPayload s) {
        require(s.sku() != null && !s.sku().isBlank(), "sku missing");
        index.upsertStock(s.sku(), IndexUpdates.stockParams(s, clock.instant()));
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            // not retryable: the platform error handler sends IllegalArgumentException straight to the DLT
            throw new IllegalArgumentException("Invalid event payload: " + message);
        }
    }
}
