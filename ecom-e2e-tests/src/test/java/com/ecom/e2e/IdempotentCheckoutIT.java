package com.ecom.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecom.common.events.EventTopics;
import com.ecom.e2e.support.Api;
import com.ecom.e2e.support.SagaTestBase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** Scenario 7: POST /orders replayed with the same Idempotency-Key yields one order. */
class IdempotentCheckoutIT extends SagaTestBase {

    @Test
    void sequentialReplayReturnsSameOrder() {
        String sku = newSku(5, "11.00");
        Shopper s = newShopper();
        api.setCartItem(s.userId(), sku, 1);
        String key = UUID.randomUUID().toString();

        Api.Resp first = api.checkout(s.userId(), key, VISA, s.addressId()).expect(202);
        Api.Resp replay = api.checkout(s.userId(), key, VISA, s.addressId()).expect(200);
        assertThat(replay.text("orderId")).isEqualTo(first.text("orderId"));

        String orderId = first.text("orderId");
        awaitStatus(s, orderId, "CONFIRMED");
        // replay after the saga finished still returns the same order, now CONFIRMED
        Api.Resp late = api.checkout(s.userId(), key, VISA, s.addressId()).expect(200);
        assertThat(late.text("orderId")).isEqualTo(orderId);
        assertThat(late.text("status")).isEqualTo("CONFIRMED");

        // same key, different body -> 422, still one order
        api.checkout(s.userId(), key, DECLINED, s.addressId()).expect(422);

        assertThat(db.orderCount(s.userId())).isEqualTo(1);
        assertThat(api.myOrders(s.userId()).path("totalElements").asLong()).isEqualTo(1);
        assertThat(sys.events().count(EventTopics.ORDER_CREATED, orderId)).isEqualTo(1);
        awaitStock(sku, 4, 0);
    }

    @Test
    void concurrentReplaysReturnSameOrder() throws Exception {
        String sku = newSku(5, "11.00");
        Shopper s = newShopper();
        api.setCartItem(s.userId(), sku, 1);
        String key = UUID.randomUUID().toString();

        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<CompletableFuture<Api.Resp>> calls = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                calls.add(CompletableFuture.supplyAsync(() -> api.checkout(s.userId(), key, VISA, s.addressId()), pool));
            }
            Set<String> orderIds = new HashSet<>();
            int created = 0;
            for (var c : calls) {
                Api.Resp r = c.get().expect(200, 202);
                orderIds.add(r.text("orderId"));
                if (r.status() == 202) {
                    created++;
                }
            }
            assertThat(orderIds).hasSize(1);
            assertThat(created).isEqualTo(1);
            String orderId = orderIds.iterator().next();
            awaitStatus(s, orderId, "CONFIRMED");
            assertThat(db.orderCount(s.userId())).isEqualTo(1);
            awaitStock(sku, 4, 0);
        } finally {
            pool.shutdownNow();
        }
    }
}
