package com.ecom.common.events.payload;

import java.util.List;

public record ProductDeletedPayload(String productId, List<String> skus, long version) {}
