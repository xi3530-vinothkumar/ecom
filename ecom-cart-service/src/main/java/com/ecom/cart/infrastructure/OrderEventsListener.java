package com.ecom.cart.infrastructure;

import com.ecom.cart.application.CartService;
import com.ecom.cart.domain.CartOwner;
import com.ecom.common.events.EventTopics;
import com.ecom.common.events.payload.OrderConfirmedPayload;
import com.ecom.common.events.payload.OrderLinePayload;
import com.ecom.platform.kafka.IdempotentConsumer;
import java.util.Map;
import java.util.TreeMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** order-confirmed: take the ordered quantities out of the user's cart (never the whole cart). */
@Component
public class OrderEventsListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventsListener.class);
    public static final String GROUP = "cart-service-order-events-cg";

    private final IdempotentConsumer consumer;
    private final RedisProcessedEventStore dedup;
    private final CartService carts;

    public OrderEventsListener(IdempotentConsumer consumer, RedisProcessedEventStore dedup, CartService carts) {
        this.consumer = consumer;
        this.dedup = dedup;
        this.carts = carts;
    }

    @KafkaListener(topics = EventTopics.ORDER_CONFIRMED, groupId = GROUP)
    public void onOrderConfirmed(ConsumerRecord<String, String> rec) {
        consumer.handle(rec, GROUP, env -> {
            try {
                OrderConfirmedPayload p = env.payloadAs(consumer.mapper(), OrderConfirmedPayload.class);
                CartOwner.User user = new CartOwner.User(p.userId());
                long changed = carts.removeOrdered(user, ordered(p));
                log.info("Order {} confirmed: decremented {} line(s) in cart of user {}", p.orderId(), changed, p.userId());
            } catch (RuntimeException e) {
                dedup.forget(env.eventId(), GROUP);
                throw e;
            }
        });
    }

    private static Map<String, Integer> ordered(OrderConfirmedPayload p) {
        Map<String, Integer> totals = new TreeMap<>();
        if (p.lines() != null) {
            for (OrderLinePayload line : p.lines()) {
                if (line.sku() != null && line.quantity() > 0) {
                    totals.merge(line.sku(), line.quantity(), Math::addExact);
                }
            }
        }
        return totals;
    }
}
