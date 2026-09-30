package com.ecom.inventory.application;

import com.ecom.inventory.domain.StockLevel;
import com.ecom.inventory.infrastructure.StockLevelRepository;
import com.ecom.platform.core.EcomException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockAdminService {

    private final StockLevelRepository stock;
    private final StockEvents stockEvents;

    public StockAdminService(StockLevelRepository stock, StockEvents stockEvents) {
        this.stock = stock;
        this.stockEvents = stockEvents;
    }

    /** Sets the physical count; creates the SKU row if needed. Rejects counts below what is already reserved. */
    @Transactional
    public StockLevel setOnHand(String sku, int onHand) {
        if (onHand < 0) {
            throw EcomException.validation("onHand must be >= 0");
        }
        boolean created = stock.insertIfAbsent(sku);
        StockLevel current = stock.lockForUpdate(List.of(sku)).get(sku);
        if (onHand < current.reserved()) {
            throw EcomException.unprocessable("onHand " + onHand + " is below the reserved quantity "
                    + current.reserved() + " for SKU " + sku);
        }
        if (!created && onHand == current.onHand()) {
            return current;
        }
        StockLevel updated = stock.setOnHand(sku, onHand);
        if (created || updated.available() != current.available()) {
            stockEvents.availabilityChanged(updated);
        }
        return updated;
    }

    @Transactional(readOnly = true)
    public StockLevel get(String sku) {
        return stock.find(sku).orElseThrow(() -> EcomException.notFound("No stock record for SKU " + sku));
    }
}
