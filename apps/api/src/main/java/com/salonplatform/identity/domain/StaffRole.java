package com.salonplatform.identity.domain;

import java.util.Set;

public enum StaffRole {
    OWNER,
    MANAGER,
    RECEPTIONIST,
    PROFESSIONAL,
    ACCOUNTANT;

    public Set<String> permissions(boolean grant) {
        java.util.HashSet<String> p = new java.util.HashSet<>(Set.of(
                "session.manage",
                "password.change",
                "mfa.enroll",
                "entitlement.read",
                // The service catalogue in the workspace (F05, decision 093): every role reads it.
                "catalogue.read"));
        if (this == OWNER || this == MANAGER)
            p.addAll(Set.of(
                    "staff.list",
                    "audit.list",
                    "payment.refund",
                    "catalogue.price.change",
                    "policy.change",
                    "customer.export",
                    // Salon settings (F04, decision 085): reading them, and changing and publishing them.
                    "settings.read",
                    "settings.manage"));
        if (this == OWNER) p.addAll(Set.of("staff.manage", "invoice.read", "support.manage"));
        if (this == OWNER || this == MANAGER || this == RECEPTIONIST || this == PROFESSIONAL)
            p.addAll(Set.of("calendar.read", "customer.read"));
        if (this == OWNER || this == MANAGER || this == ACCOUNTANT || (this == PROFESSIONAL && grant))
            p.add("financial.read");
        return Set.copyOf(p);
    }

    /**
     * What a support session may read (F03-S04): the Owner's read permissions and nothing else; never
     * its own-account, management or support-access permissions.
     */
    public static final Set<String> SUPPORT_READS = Set.of(
            "entitlement.read",
            "staff.list",
            "audit.list",
            "invoice.read",
            "calendar.read",
            "customer.read",
            "financial.read",
            // F04 (decision 085): a support session reads salon settings and never changes them.
            "settings.read",
            // F05 (decision 093): and the service catalogue.
            "catalogue.read");

    public String scope(boolean grant) {
        return this == PROFESSIONAL && !grant ? "own" : "salon";
    }
}
