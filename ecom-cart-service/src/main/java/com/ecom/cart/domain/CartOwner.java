package com.ecom.cart.domain;

import java.util.UUID;

/** Whose cart: an authenticated user (no expiry) or an anonymous guest (sliding TTL). */
public sealed interface CartOwner {

    String redisKey();

    record User(String userId) implements CartOwner {
        public User {
            if (userId == null || userId.isBlank()) {
                throw new IllegalArgumentException("userId is required");
            }
        }

        @Override
        public String redisKey() {
            return "cart:user:" + userId;
        }
    }

    record Guest(UUID guestId) implements CartOwner {
        public Guest {
            if (guestId == null) {
                throw new IllegalArgumentException("guestId is required");
            }
        }

        @Override
        public String redisKey() {
            return "cart:guest:" + guestId;
        }
    }
}
