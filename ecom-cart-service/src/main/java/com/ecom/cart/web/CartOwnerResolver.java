package com.ecom.cart.web;

import com.ecom.cart.domain.CartOwner;
import com.ecom.platform.core.CallContext;
import com.ecom.platform.core.EcomException;
import java.util.Optional;
import java.util.UUID;

/** Authenticated user (Gateway-stamped X-User-Id) wins; otherwise the guest cart header; otherwise 400. */
final class CartOwnerResolver {

    static final String GUEST_HEADER = "X-Guest-Cart-Id";

    static CartOwner resolve(String guestHeader) {
        Optional<String> userId = CallContext.current().map(CallContext::userId).filter(id -> !id.isBlank());
        if (userId.isPresent()) {
            return new CartOwner.User(userId.get());
        }
        if (guestHeader == null || guestHeader.isBlank()) {
            throw EcomException.validation("Authenticate or send " + GUEST_HEADER + " (obtain one via POST /cart/guest)");
        }
        return new CartOwner.Guest(parseGuestId(guestHeader.trim()));
    }

    static UUID parseGuestId(String raw) {
        try {
            UUID id = UUID.fromString(raw);
            // UUID.fromString accepts non-canonical forms like "1-1-1-1-1"
            if (id.toString().equalsIgnoreCase(raw)) {
                return id;
            }
        } catch (IllegalArgumentException _) {
            // fall through
        }
        throw EcomException.validation(GUEST_HEADER + " must be a UUID");
    }

    private CartOwnerResolver() {}
}
