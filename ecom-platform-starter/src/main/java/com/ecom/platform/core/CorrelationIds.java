package com.ecom.platform.core;

import java.util.UUID;
import java.util.regex.Pattern;

public final class CorrelationIds {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** Accept an inbound id only if it is a well-formed UUID (prevents log injection); else mint one. */
    public static String acceptOrGenerate(String inbound) {
        return inbound != null && UUID_PATTERN.matcher(inbound).matches() ? inbound : UUID.randomUUID().toString();
    }

    private CorrelationIds() {}
}
