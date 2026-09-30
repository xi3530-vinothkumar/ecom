package com.ecom.platform.kafka;

import com.ecom.common.events.EventEnvelope;
import com.ecom.platform.core.CallContext;
import com.ecom.platform.core.CorrelationIds;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Template method every {@code @KafkaListener} routes through:
 * parse envelope -> [tx: mark processed -> handle -> (handler writes state + outbox rows)] -> commit.
 * A duplicate delivery is skipped; a handler exception rolls everything back and the container's
 * error handler retries (1s/5s/15s) then dead-letters to {@code <topic>.dlt}.
 *
 * <pre>
 * &#64;KafkaListener(topics = EventTopics.ORDER_CREATED, groupId = "inventory-service-order-events-cg")
 * void on(ConsumerRecord&lt;String, String&gt; rec) {
 *     consumer.handle(rec, "inventory-service-order-events-cg", env -&gt; reserve(env.payloadAs(mapper, OrderCreatedPayload.class)));
 * }
 * </pre>
 */
public class IdempotentConsumer {

    private static final Logger log = LoggerFactory.getLogger(IdempotentConsumer.class);

    private final ProcessedEventStore store;
    private final TransactionTemplate tx; // null when the service has no relational DB
    private final ObjectMapper mapper;

    public IdempotentConsumer(ProcessedEventStore store, TransactionTemplate tx, ObjectMapper mapper) {
        this.store = store;
        this.tx = tx;
        this.mapper = mapper;
    }

    public void handle(ConsumerRecord<String, String> record, String consumer, Consumer<EventEnvelope> handler) {
        EventEnvelope envelope = EventEnvelope.parse(mapper, record.value());
        CallContext.set(new CallContext(CorrelationIds.acceptOrGenerate(envelope.correlationId()), null, null));
        try {
            Runnable work = () -> {
                if (store.markProcessed(envelope.eventId(), consumer)) {
                    handler.accept(envelope);
                } else {
                    log.debug("Skipping duplicate event {} ({}) for {}", envelope.eventId(), envelope.eventType(), consumer);
                }
            };
            if (tx != null) {
                tx.executeWithoutResult(s -> work.run());
            } else {
                // no transaction: the marker is already written, so remove it on failure to allow the retry
                try {
                    work.run();
                } catch (RuntimeException e) {
                    store.unmark(envelope.eventId(), consumer);
                    throw e;
                }
            }
        } finally {
            CallContext.clear();
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
