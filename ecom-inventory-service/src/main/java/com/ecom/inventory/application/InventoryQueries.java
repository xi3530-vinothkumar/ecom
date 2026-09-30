package com.ecom.inventory.application;

import com.ecom.inventory.domain.ReservationLine;
import com.ecom.inventory.domain.ReservationStatus;
import com.ecom.inventory.domain.SkuQuantity;
import com.ecom.inventory.domain.StockLevel;
import com.ecom.inventory.infrastructure.ReservationRepository;
import com.ecom.inventory.infrastructure.StockLevelRepository;
import com.ecom.platform.core.EcomException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side for the gRPC API (never locks, never mutates). */
@Service
public class InventoryQueries {

    public record Availability(String sku, int available, boolean sufficient) {}

    public record OrderReservation(
            UUID orderId, ReservationStatus status, Instant expiresAt, List<SkuQuantity> lines) {}

    private final StockLevelRepository stock;
    private final ReservationRepository reservations;

    public InventoryQueries(StockLevelRepository stock, ReservationRepository reservations) {
        this.stock = stock;
        this.reservations = reservations;
    }

    @Transactional(readOnly = true)
    public List<Availability> checkAvailability(List<SkuQuantity> items) {
        SortedMap<String, Integer> requested = SkuQuantity.aggregate(items);
        Map<String, StockLevel> levels = stock.findAll(requested.keySet());
        return requested.entrySet().stream()
                .map(e -> {
                    StockLevel level = levels.get(e.getKey());
                    int available = level == null ? 0 : Math.max(0, level.available());
                    return new Availability(e.getKey(), available, available >= e.getValue());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderReservation getReservation(UUID orderId) {
        List<ReservationLine> lines = reservations.findByOrder(orderId);
        if (lines.isEmpty()) {
            throw EcomException.notFound("No reservation for order " + orderId);
        }
        ReservationStatus status = ReservationStatus.aggregate(lines.stream().map(ReservationLine::status).toList());
        Instant expiresAt = lines.stream().map(ReservationLine::expiresAt).min(Comparator.naturalOrder()).orElseThrow();
        return new OrderReservation(
                orderId, status, expiresAt,
                lines.stream().map(l -> new SkuQuantity(l.sku(), l.quantity())).toList());
    }
}
