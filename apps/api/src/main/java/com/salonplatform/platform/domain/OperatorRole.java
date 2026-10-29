package com.salonplatform.platform.domain;

import java.util.Optional;
import java.util.Set;

/**
 * The two operator roles and what each may do. Support finds salons and starts its own support
 * sessions (F03-S04); Admin manages salons, plans and billing and oversees every support session. No
 * operator role alone carries any tenant authority: a workspace is entered only through a support
 * session's single-use entry code.
 */
public enum OperatorRole {
    OPERATOR_ADMIN(Set.of(
            OperatorPermission.SESSION_MANAGE,
            OperatorPermission.SALON_READ,
            OperatorPermission.SALON_CREATE,
            OperatorPermission.PLAN_READ,
            OperatorPermission.PLAN_CREATE,
            OperatorPermission.BILLING_READ,
            OperatorPermission.BILLING_MANAGE,
            OperatorPermission.SALON_SUSPEND,
            OperatorPermission.SALON_CLOSE,
            OperatorPermission.EXPORT_READ,
            OperatorPermission.SUPPORT_READ,
            OperatorPermission.SUPPORT_REVOKE)),

    /** From F03-S04 Support finds salons and starts its own audited, expiring support sessions. */
    OPERATOR_SUPPORT(
            Set.of(OperatorPermission.SESSION_MANAGE, OperatorPermission.SALON_READ, OperatorPermission.SUPPORT_START));

    private final Set<String> permissions;

    OperatorRole(Set<String> permissions) {
        this.permissions = permissions;
    }

    public Set<String> permissions() {
        return permissions;
    }

    public static Optional<OperatorRole> of(String name) {
        for (OperatorRole role : values()) {
            if (role.name().equals(name)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
