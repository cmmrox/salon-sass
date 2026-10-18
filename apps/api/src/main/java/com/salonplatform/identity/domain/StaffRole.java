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
                new java.util.HashSet<>(
                        Set.of(
                                "session.manage",
                                "password.change",
                                "mfa.enroll",
                                "entitlement.read"));
        if (this == OWNER || this == MANAGER)
            p.addAll(
                    Set.of(
                            "staff.list",
                            "audit.list",
                            "payment.refund",
                            "catalogue.price.change",
                            "policy.change",
                            "customer.export"));
        if (this == OWNER) p.addAll(Set.of("staff.manage", "invoice.read"));
        if (this == OWNER || this == MANAGER || this == RECEPTIONIST || this == PROFESSIONAL)
            p.addAll(Set.of("calendar.read", "customer.read"));
        if (this == OWNER
                || this == MANAGER
                || this == ACCOUNTANT
                || (this == PROFESSIONAL && grant)) p.add("financial.read");
        return Set.copyOf(p);
    }

    public String scope(boolean grant) {
        return this == PROFESSIONAL && !grant ? "own" : "salon";
    }
}
