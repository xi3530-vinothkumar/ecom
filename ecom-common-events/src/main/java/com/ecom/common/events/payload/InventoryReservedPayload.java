package com.ecom.common.events.payload;

import java.time.Instant;

public record InventoryReservedPayload(String orderId, Instant expiresAt) {}
