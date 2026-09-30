package com.ecom.inventory.domain;

import java.time.Instant;
import java.util.UUID;

public record ReservationLine(UUID orderId, String sku, int quantity, ReservationStatus status, Instant expiresAt) {}
