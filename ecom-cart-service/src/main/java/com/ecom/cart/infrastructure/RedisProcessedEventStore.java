package com.ecom.cart.infrastructure;

import com.ecom.cart.config.CartProperties;
import com.ecom.platform.kafka.ProcessedEventStore;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis dedup for the idempotent consumer (Cart has no relational DB, hence no transaction):
 * {@code SET processed:{consumer}:{eventId} NX EX <ttl>}. Defining this bean activates the starter's
 * IdempotentConsumer.
 */
@Component
public class RedisProcessedEventStore implements ProcessedEventStore {

    private final StringRedisTemplate redis;
    private final CartProperties props;

    public RedisProcessedEventStore(StringRedisTemplate redis, CartProperties props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    public boolean markProcessed(UUID eventId, String consumer) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(eventId, consumer), "1", props.processedEventTtl()));
    }

    /**
     * Undoes {@link #markProcessed} when the handler failed, so the container's retry reprocesses the event
     * (without a transaction the marker would otherwise survive the failure and the event would be lost).
     */
    public void forget(UUID eventId, String consumer) {
        redis.delete(key(eventId, consumer));
    }

    static String key(UUID eventId, String consumer) {
        return "processed:" + consumer + ":" + eventId;
    }
}
