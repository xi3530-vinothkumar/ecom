package com.ecom.user.web;

import com.ecom.platform.core.CallContext;
import com.ecom.platform.core.EcomException;
import java.util.UUID;

final class CurrentUser {

    static UUID id() {
        String raw = CallContext.requireUserId();
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw EcomException.unauthenticated("Authentication required");
        }
    }

    private CurrentUser() {}
}
