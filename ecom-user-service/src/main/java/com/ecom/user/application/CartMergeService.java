package com.ecom.user.application;

import com.ecom.user.infrastructure.CartMergeClient;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Guest-cart merge at login. Best effort: a Cart outage must never block a login. */
@Service
public class CartMergeService {

    private static final Logger log = LoggerFactory.getLogger(CartMergeService.class);

    private final CartMergeClient client;

    public CartMergeService(CartMergeClient client) {
        this.client = client;
    }

    public void mergeBestEffort(String guestCartId, UUID userId, Set<String> roles) {
        if (guestCartId == null || guestCartId.isBlank()) {
            return;
        }
        try {
            client.merge(guestCartId, userId, roles);
        } catch (RuntimeException e) {
            log.warn("Guest cart merge failed for userId={} guestCartId={}: {}", userId, guestCartId, e.getMessage());
        }
    }
}
