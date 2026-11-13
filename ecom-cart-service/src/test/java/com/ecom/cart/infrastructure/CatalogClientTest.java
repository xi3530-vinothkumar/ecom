package com.ecom.cart.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecom.cart.config.CacheConfiguration;
import com.ecom.catalog.proto.v1.GetProductsBatchResponse;
import com.ecom.catalog.proto.v1.ProductItem;
import com.ecom.platform.grpc.GrpcChannels;
import com.ecom.platform.grpc.ResilientCalls;
import io.grpc.Channel;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

class CatalogClientTest {

    private final ResilientCalls calls = mock(ResilientCalls.class);
    private final CatalogClient client;

    CatalogClientTest() {
        GrpcChannels channels = mock(GrpcChannels.class);
        when(channels.channel("catalog")).thenReturn(mock(Channel.class));
        client = new CatalogClient(channels, calls, new ConcurrentMapCacheManager(CacheConfiguration.CATALOG_PRODUCTS));
    }

    @Test
    void loadsMissesInOneBatchAndCachesUnknownSkusAsAbsent() {
        when(calls.call(eq("catalog"), any(Supplier.class))).thenReturn(GetProductsBatchResponse.newBuilder()
                .addItems(ProductItem.newBuilder().setSku("A").setName("Mug").setActive(true))
                .build());

        assertThat(client.products(List.of("A", "GONE"))).containsOnlyKeys("A");
        assertThat(client.products(List.of("A", "GONE"))).containsOnlyKeys("A");
        assertThat(client.product("GONE")).isEmpty();

        verify(calls, times(1)).call(eq("catalog"), any(Supplier.class));
    }
}
