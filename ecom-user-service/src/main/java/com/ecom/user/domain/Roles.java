package com.ecom.user.domain;

import java.util.Set;

public final class Roles {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String ADMIN = "ADMIN";

    public static final Set<String> ALL = Set.of(CUSTOMER, ADMIN);

    private Roles() {}
}
