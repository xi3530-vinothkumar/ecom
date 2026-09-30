package com.ecom.user.domain;

import java.util.Locale;

public final class Emails {

    /** Canonical form used for storage and lookup: e-mail identity is case-insensitive here. */
    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private Emails() {}
}
