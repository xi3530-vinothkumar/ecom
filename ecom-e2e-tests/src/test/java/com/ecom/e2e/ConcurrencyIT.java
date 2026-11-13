package com.ecom.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecom.e2e.support.Api;
import com.ecom.e2e.support.SagaTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** N shoppers race for the last M units of one SKU: exactly M orders confirm, stock never goes negative. */
class ConcurrencyIT extends SagaTestBase {

    @Test
    void lastUnitsAreSoldExactlyOnce() throws Exception {
        int units = 3;
        int shoppers = 10;
        String sku = newSku(units, "99.00");
        List<Shopper> all = new ArrayList<>();
        for (int i = 0; i < shoppers; i++) {
            Shopper s = newShopper();
            api.setCartItem(s.userId(), sku, 1);
            all.add(s);
        }

        // watch stock the whole time
        AtomicBoolean watching = new AtomicBoolean(true);
        AtomicReference<String> violation = new AtomicReference<>();
        Thread watcher = new Thread(() -> {
            while (watching.get()) {
                Api.Stock st = api.stock(sku);
                if (st.onHand() < 0 || st.reserved() < 0 || st.available() < 0 || st.reserved() > st.onHand()) {
                    violation.compareAndSet(null, st.toString());
                }
            }
        }, "stock-watcher");
        watcher.start();

        ExecutorService pool = Executors.newFixedThreadPool(shoppers);
        try {
            List<CompletableFuture<String>> checkouts = new ArrayList<>();
            for (Shopper s : all) {
                checkouts.add(CompletableFuture.supplyAsync(() -> api.checkout(s.userId(),
                        java.util.UUID.randomUUID().toString(), VISA, s.addressId()).expect(202).text("orderId"), pool));
            }
            List<String> statuses = new ArrayList<>();
            for (int i = 0; i < shoppers; i++) {
                String orderId = checkouts.get(i).get();
                var order = awaitTerminal(all.get(i), orderId);
                String status = order.path("status").asString();
                if (status.equals("CANCELLED")) {
                    assertThat(order.path("cancelReason").asString()).isEqualTo("OUT_OF_STOCK");
                }
                statuses.add(status);
            }
            assertThat(statuses.stream().filter("CONFIRMED"::equals).count()).as("%s", statuses).isEqualTo(units);
            assertThat(statuses.stream().filter("CANCELLED"::equals).count()).isEqualTo(shoppers - units);
            awaitStock(sku, 0, 0);
        } finally {
            watching.set(false);
            watcher.join();
            pool.shutdownNow();
        }
        assertThat(violation.get()).as("stock invariant violated").isNull();
    }
}
