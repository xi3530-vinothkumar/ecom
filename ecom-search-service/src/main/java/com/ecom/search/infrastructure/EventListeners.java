package com.ecom.search.infrastructure;

import com.ecom.common.events.EventEnvelope;
import com.ecom.common.events.EventTopics;
import com.ecom.common.events.payload.ProductChangedPayload;
import com.ecom.common.events.payload.ProductDeletedPayload;
import com.ecom.common.events.payload.StockLevelChangedPayload;
import com.ecom.platform.core.CallContext;
import com.ecom.platform.core.CorrelationIds;
import com.ecom.search.application.IndexingService;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Two independent consumer groups so a slow inventory stream never delays catalog indexing.
 * Idempotency comes from versioned upserts, not from a processed-events store.
 */
@Component
public class EventListeners {

    public static final String CATALOG_GROUP = "search-service-catalog-indexer-cg";
    public static final String INVENTORY_GROUP = "search-service-inventory-indexer-cg";

    private static final Logger log = LoggerFactory.getLogger(EventListeners.class);

    private final IndexingService indexing;
    private final ObjectMapper mapper;

    public EventListeners(IndexingService indexing, ObjectMapper mapper) {
        this.indexing = indexing;
        this.mapper = mapper;
    }

    @KafkaListener(
            topics = {EventTopics.PRODUCT_CREATED, EventTopics.PRODUCT_UPDATED, EventTopics.PRODUCT_DELETED},
            groupId = CATALOG_GROUP)
    public void onCatalog(ConsumerRecord<String, String> rec) {
        handle(rec, env -> {
            switch (rec.topic()) {
                case EventTopics.PRODUCT_CREATED, EventTopics.PRODUCT_UPDATED ->
                        indexing.onProductChanged(env.payloadAs(mapper, ProductChangedPayload.class));
                case EventTopics.PRODUCT_DELETED ->
                        indexing.onProductDeleted(env.payloadAs(mapper, ProductDeletedPayload.class));
                default -> log.warn("Ignoring record from unexpected topic {}", rec.topic());
            }
        });
    }

    @KafkaListener(topics = EventTopics.STOCK_LEVEL_CHANGED, groupId = INVENTORY_GROUP)
    public void onStock(ConsumerRecord<String, String> rec) {
        handle(rec, env -> indexing.onStockChanged(env.payloadAs(mapper, StockLevelChangedPayload.class)));
    }

    private void handle(ConsumerRecord<String, String> rec, Consumer<EventEnvelope> handler) {
        EventEnvelope env = EventEnvelope.parse(mapper, rec.value());
        CallContext.set(new CallContext(CorrelationIds.acceptOrGenerate(env.correlationId()), null, null));
        try {
            handler.accept(env);
        } finally {
            CallContext.clear();
        }
    }
}
