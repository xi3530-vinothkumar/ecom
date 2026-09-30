package com.ecom.inventory.application;

import com.ecom.common.events.EventTopics;
import com.ecom.common.events.payload.StockLevelChangedPayload;
import com.ecom.inventory.domain.StockLevel;
import com.ecom.platform.kafka.OutboxWriter;
import org.springframework.stereotype.Component;

/** Emits stock-level-changed for a row whose available quantity changed (caller's transaction). */
@Component
public class StockEvents {

    private final OutboxWriter outbox;

    public StockEvents(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    public void availabilityChanged(StockLevel level) {
        outbox.publish(
                EventTopics.STOCK_LEVEL_CHANGED,
                level.sku(),
                new StockLevelChangedPayload(level.sku(), level.available(), level.inStock(), level.version()));
    }
}
