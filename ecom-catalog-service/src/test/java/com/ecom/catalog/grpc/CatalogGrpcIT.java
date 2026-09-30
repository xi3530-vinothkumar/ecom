package com.ecom.catalog.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecom.catalog.CatalogIntegrationTest;
import com.ecom.catalog.proto.v1.CatalogServiceGrpc;
import com.ecom.catalog.proto.v1.GetProductRequest;
import com.ecom.catalog.proto.v1.GetProductsBatchRequest;
import com.ecom.catalog.proto.v1.ListCategoriesRequest;
import com.ecom.catalog.proto.v1.ProductItem;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

class CatalogGrpcIT extends CatalogIntegrationTest {

    @Autowired GrpcServerLifecycle server;

    private ManagedChannel channel;
    private CatalogServiceGrpc.CatalogServiceBlockingStub stub;

    @BeforeEach
    void connect() {
        channel = NettyChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
        stub = CatalogServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void close() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void batchReturnsKnownSkusFlagsInactiveAndOmitsUnknown() throws Exception {
        String category = createCategory("Grpc " + sku());
        String live = sku();
        String retired = sku();
        String productId = createProduct(category, List.of(variant(live, "19.99"), variant(retired, "5")));
        mvc.perform(asAdmin(json(put("/admin/products/" + productId),
                        product("Renamed", category, List.of(variant(live, "19.99"))))))
                .andExpect(status().isOk());

        String hiddenSku = sku();
        var hidden = new HashMap<>(product("Hidden", category, List.of(variant(hiddenSku, "1"))));
        hidden.put("active", false);
        mvc.perform(asAdmin(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/products"), hidden)))
                .andExpect(status().isCreated());

        var response = stub.getProductsBatch(GetProductsBatchRequest.newBuilder()
                .addAllSkus(List.of(live, "SKU-DOES-NOT-EXIST", retired, hiddenSku, live))
                .build());

        Map<String, ProductItem> bySku =
                response.getItemsList().stream().collect(Collectors.toMap(ProductItem::getSku, i -> i));
        assertThat(response.getItemsList()).extracting(ProductItem::getSku).containsExactly(live, retired, hiddenSku);
        ProductItem item = bySku.get(live);
        assertThat(item.getActive()).isTrue();
        assertThat(item.getName()).isEqualTo("Renamed");
        assertThat(item.getProductId()).isEqualTo(productId);
        assertThat(item.getCategoryId()).isEqualTo(category);
        assertThat(item.getPrice().getCurrencyCode()).isEqualTo("EUR");
        assertThat(item.getPrice().getUnits()).isEqualTo(19);
        assertThat(item.getPrice().getNanos()).isEqualTo(990_000_000);
        assertThat(bySku.get(retired).getActive()).isFalse();
        assertThat(bySku.get(hiddenSku).getActive()).isFalse();
    }

    @Test
    void getProductBySkuAndNotFound() throws Exception {
        String s = sku();
        createProduct(createCategory("Single " + sku()), List.of(variant(s, "2.5")));

        ProductItem item = stub.getProduct(GetProductRequest.newBuilder().setSku(s).build());
        assertThat(item.getPrice().getUnits()).isEqualTo(2);
        assertThat(item.getPrice().getNanos()).isEqualTo(500_000_000);

        assertThatThrownBy(() -> stub.getProduct(GetProductRequest.newBuilder().setSku("NOPE-" + s).build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
    }

    @Test
    void listCategories() throws Exception {
        String id = createCategory("Listed " + sku());
        var categories = stub.listCategories(ListCategoriesRequest.getDefaultInstance()).getCategoriesList();
        assertThat(categories).anySatisfy(c -> assertThat(c.getCategoryId()).isEqualTo(id));
    }
}
