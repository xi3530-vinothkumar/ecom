package com.ecom.inventory.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecom.common.events.EventTopics;
import com.ecom.common.events.payload.OrderCreatedPayload;
import com.ecom.inventory.infrastructure.OrderEventsListener;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReservationConcurrencyIT extends AbstractInventoryIT {

    @Autowired OrderEventsListener listener;

    @Test
    void parallelOrdersNeverOversell() throws Exception {
        String hot = sku("HOT");
        stock(hot, 5);
        int orders = 20;
        List<UUID> orderIds = new ArrayList<>();
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        for (int i = 0; i < orders; i++) {
            UUID id = UUID.randomUUID();
            orderIds.add(id);
            records.add(record(EventTopics.ORDER_CREATED, id.toString(),
                    new OrderCreatedPayload(id.toString(), "user-" + i, lines(hot, 1))));
        }

        ExecutorService pool = Executors.newFixedThreadPool(orders);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (var rec : records) {
                futures.add(pool.submit(() -> {
                    start.await();
                    listener.onOrderCreated(rec);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(level(hot).reserved()).isEqualTo(5);
        assertThat(level(hot).onHand()).isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM stock_reservations WHERE sku = ? AND status = 'RESERVED'", Integer.class, hot))
                .isEqualTo(5);
        long reserved = orderIds.stream()
                .filter(id -> !outboxEvents(EventTopics.INVENTORY_RESERVED, id.toString()).isEmpty())
                .count();
        long failed = orderIds.stream()
                .filter(id -> !outboxEvents(EventTopics.INVENTORY_RESERVATION_FAILED, id.toString()).isEmpty())
                .count();
        assertThat(reserved).isEqualTo(5);
        assertThat(failed).isEqualTo(15);
        // version strictly increases with every reservation of the row
        assertThat(level(hot).version()).isEqualTo(5);
    }

    @Test
    void parallelMultiSkuOrdersInOppositeLineOrderDoNotDeadlock() throws Exception {
        String x = sku("X"), y = sku("Y");
        stock(x, 100);
        stock(y, 100);
        int orders = 20;
        ExecutorService pool = Executors.newFixedThreadPool(orders);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < orders; i++) {
                UUID id = UUID.randomUUID();
                var lines = i % 2 == 0 ? lines(x, 1, y, 1) : lines(y, 1, x, 1);
                var rec = record(EventTopics.ORDER_CREATED, id.toString(),
                        new OrderCreatedPayload(id.toString(), "user", lines));
                futures.add(pool.submit(() -> {
                    start.await();
                    listener.onOrderCreated(rec);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(level(x).reserved()).isEqualTo(orders);
        assertThat(level(y).reserved()).isEqualTo(orders);
    }
}
