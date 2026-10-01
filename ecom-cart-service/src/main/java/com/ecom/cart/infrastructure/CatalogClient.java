package com.ecom.cart.infrastructure;

import com.ecom.cart.config.CacheConfiguration;
import com.ecom.cart.domain.Product;
import com.ecom.catalog.proto.v1.CatalogServiceGrpc;
import com.ecom.catalog.proto.v1.GetProductsBatchRequest;
import com.ecom.catalog.proto.v1.GetProductsBatchResponse;
import com.ecom.catalog.proto.v1.ProductItem;
import com.ecom.platform.core.MoneyMapper;
import com.ecom.platform.grpc.GrpcChannels;
import com.ecom.platform.grpc.ResilientCalls;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * Catalog lookups for display hydration, behind a short-lived Spring Cache (Caffeine) L1; misses are
 * fetched in one batch call. Unknown SKUs are cached as absent (null) too, so a cart full of deleted
 * products does not hammer Catalog. Display-only: checkout re-prices against Catalog directly.
 */
@Component
public class CatalogClient {

    private static final String CATALOG = "catalog";

    private final CatalogServiceGrpc.CatalogServiceBlockingStub stub;
    private final ResilientCalls calls;
    private final Cache cache;

    public CatalogClient(GrpcChannels channels, ResilientCalls calls, CacheManager caches) {
        this.stub = CatalogServiceGrpc.newBlockingStub(channels.channel(CATALOG));
        this.calls = calls;
        this.cache = Objects.requireNonNull(caches.getCache(CacheConfiguration.CATALOG_PRODUCTS));
    }

    /** Known SKUs only (active or not); unknown SKUs are absent from the result. */
    public Map<String, Product> products(Collection<String> skus) {
        if (skus.isEmpty()) {
            return Map.of();
        }
        Map<String, Product> out = new LinkedHashMap<>();
        Set<String> missing = new LinkedHashSet<>();
        for (String sku : Set.copyOf(skus)) {
            switch (cache.get(sku)) {
                case null -> missing.add(sku);
                case Cache.ValueWrapper hit when hit.get() instanceof Product p -> out.put(sku, p);
                case Cache.ValueWrapper _ -> { } // cached as unknown
            }
        }
        if (!missing.isEmpty()) {
            load(missing).forEach((sku, product) -> {
                cache.put(sku, product.orElse(null));
                product.ifPresent(p -> out.put(sku, p));
            });
        }
        return out;
    }

    public Optional<Product> product(String sku) {
        return Optional.ofNullable(products(Set.of(sku)).get(sku));
    }

    private Map<String, Optional<Product>> load(Set<String> missing) {
        List<String> skus = List.copyOf(missing);
        GetProductsBatchResponse response = calls.call(CATALOG, () -> stub.getProductsBatch(
                GetProductsBatchRequest.newBuilder().addAllSkus(skus).build()));
        Map<String, Optional<Product>> loaded = new HashMap<>();
        missing.forEach(sku -> loaded.put(sku, Optional.empty()));
        for (ProductItem item : response.getItemsList()) {
            if (loaded.containsKey(item.getSku())) {
                loaded.put(item.getSku(), Optional.of(new Product(
                        item.getSku(),
                        item.getName(),
                        item.getVariantName(),
                        item.hasPrice() ? MoneyMapper.fromProto(item.getPrice()) : null,
                        item.getActive())));
            }
        }
        return loaded;
    }
}
