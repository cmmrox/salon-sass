package com.salonplatform.shared.tenancy;

/** What the registry says about a salon that exists (docs/architecture/multi-tenancy.md). */
public enum TenantStatus {

    /** Open: its hostname shows the salon. */
    ACTIVE,

    /** Closed for now: its hostname answers {@code salon_suspended}, and nothing of the salon is read. */
    SUSPENDED
}
