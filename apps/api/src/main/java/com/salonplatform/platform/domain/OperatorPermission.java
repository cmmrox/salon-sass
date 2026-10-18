package com.salonplatform.platform.domain;

/**
 * Operator permission keys (docs/architecture/operator-console.md). An open set of names rather than
 * a closed enum, so later stages (billing, lifecycle, support sessions) add keys without changing how
 * a permission is checked. Use cases check a permission, never a role name.
 */
public final class OperatorPermission {

    public static final String SESSION_MANAGE = "operator.session.manage";
    public static final String SALON_READ = "operator.salon.read";
    public static final String SALON_CREATE = "operator.salon.create";
    public static final String PLAN_READ = "operator.plan.read";
    public static final String PLAN_CREATE = "operator.plan.create";

    private OperatorPermission() {
    }
}
