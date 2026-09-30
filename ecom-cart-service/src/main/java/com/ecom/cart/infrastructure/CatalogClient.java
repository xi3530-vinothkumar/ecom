package com.ecom.cart.infrastructure;

import com.ecom.cart.config.CartProperties;
import com.ecom.cart.domain.Product;
import com.ecom.catalog.proto.v1.CatalogServiceGrpc;
import com.ecom.catalog.proto.v1.GetProductsBatchRequest;
import com.ecom.catalog.proto.v1.GetProductsBatchResponse;
import com.ecom.catalog.proto.v1.ProductItem;
import com.ecom.platform.core.MoneyMapper;
import com.ecom.platform.grpc.GrpcChannels;
import com.ecom.platform.grpc.ResilientCalls;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Catalog lookups for display hydration, behind a short-lived Caffeine L1. Unknown SKUs are cached as
 * absent too, so a cart full of deleted products does not hammer Catalog. Display-only: checkout
 * re-prices against Catalog directly.
 */
@Component
public class CatalogClient {

    private static final String CATALOG = "catalog";

    private final CatalogServiceGrpc.CatalogServiceBlockingStub stub;
    private final ResilientCalls calls;
    private final Cache<String, Optional<Product>> cache;

    public CatalogClient(GrpcChannels channels, ResilientCalls calls, CartProperties props) {
        this.stub = CatalogServiceGrpc.newBlockingStub(channels.channel(CATALOG));
        this.calls = calls;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(props.catalogCache().ttl())
                .maximumSize(props.catalogCache().maxSize())
                .build();
    }

    /** Known SKUs only (active or not); unknown SKUs are absent from the result. */
    public Map<String, Product> products(Collection<String> skus) {
        if (skus.isEmpty()) {
            return Map.of();
        }
        Map<String, Optional<Product>> found = cache.getAll(Set.copyOf(skus), this::load);
        Map<String, Product> out = new LinkedHashMap<>();
        found.forEach((sku, product) -> product.ifPresent(p -> out.put(sku, p)));
        return out;
    }

    public Optional<Product> product(String sku) {
        return Optional.ofNullable(products(Set.of(sku)).get(sku));
    }

    private Map<String, Optional<Product>> load(Set<? extends String> missing) {
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
