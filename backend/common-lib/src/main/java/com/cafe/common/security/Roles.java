package com.cafe.common.security;

/**
 * Role name constants shared across services. The canonical Role enum is owned by
 * auth-service; every other service only ever sees the role as a plain string
 * (JWT claim / X-User-Role header), so these constants avoid magic strings
 * without introducing a shared entity/enum dependency.
 */
public final class Roles {

    public static final String ADMIN = "ADMIN";
    public static final String CASHIER = "CASHIER";

    private Roles() {
    }
}
