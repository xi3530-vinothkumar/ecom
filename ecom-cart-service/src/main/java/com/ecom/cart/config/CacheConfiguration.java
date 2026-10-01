package com.ecom.cart.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.List;
import org.springframework.boot.cache.autoconfigure.CacheManagerCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring Cache on Caffeine (spring.cache.type=caffeine; Redis holds carts, not this L1). */
@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CacheConfiguration {

    public static final String CATALOG_PRODUCTS = "catalog-products";

    @Bean
    CacheManagerCustomizer<CaffeineCacheManager> catalogCacheCustomizer(CartProperties props) {
        return manager -> {
            manager.setCaffeine(Caffeine.newBuilder()
                    .expireAfterWrite(props.catalogCache().ttl())
                    .maximumSize(props.catalogCache().maxSize())
                    .recordStats());
            manager.setCacheNames(List.of(CATALOG_PRODUCTS));
        };
    }
}
