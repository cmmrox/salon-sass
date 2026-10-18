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
    /** Subscriptions and invoices (F03-S02). */
    public static final String BILLING_READ = "operator.billing.read";
    /** Start a subscription, change its amount or method, move a plan, record a transfer (F03-S02). */
    public static final String BILLING_MANAGE = "operator.billing.manage";
    /** Suspend and reactivate a salon (F03-S03). */
    public static final String SALON_SUSPEND = "operator.salon.suspend";
    /** Close a salon for good (F03-S03). */
    public static final String SALON_CLOSE = "operator.salon.close";
    /** A closed salon's export status and archive (F03-S03). */
    public static final String EXPORT_READ = "operator.export.read";

    private OperatorPermission() {
    }
}
