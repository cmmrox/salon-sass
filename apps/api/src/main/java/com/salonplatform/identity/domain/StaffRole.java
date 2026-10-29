package com.salonplatform.identity.domain;

import java.util.Set;

public enum StaffRole {
    OWNER,
    MANAGER,
    RECEPTIONIST,
    PROFESSIONAL,
    ACCOUNTANT;

    public Set<String> permissions(boolean grant) {
        java.util.HashSet<String> p =
                new java.util.HashSet<>(Set.of("session.manage", "password.change", "mfa.enroll", "entitlement.read"));
        if (this == OWNER || this == MANAGER)
            p.addAll(Set.of(
                    "staff.list",
                    "audit.list",
                    "payment.refund",
                    "catalogue.price.change",
                    "policy.change",
                    "customer.export"));
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
            "financial.read");

    public String scope(boolean grant) {
        return this == PROFESSIONAL && !grant ? "own" : "salon";
    }
}
