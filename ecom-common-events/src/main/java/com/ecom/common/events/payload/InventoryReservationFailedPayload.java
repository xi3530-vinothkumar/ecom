package com.ecom.common.events.payload;

import java.util.List;

public record InventoryReservationFailedPayload(String orderId, List<String> shortSkus) implements EventPayload {}
