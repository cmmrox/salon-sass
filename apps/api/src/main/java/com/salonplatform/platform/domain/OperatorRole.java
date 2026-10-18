package com.salonplatform.platform.domain;

import java.util.Optional;
import java.util.Set;

/**
 * The two operator roles and what each may do in F03-S01. Support has its own principal and session
 * operations only; salon management and support sessions are not its authority here (F03-S04 adds
 * the separate, expiring support grant). No operator role carries any tenant authority.
 */
public enum OperatorRole {

    OPERATOR_ADMIN(Set.of(OperatorPermission.SESSION_MANAGE, OperatorPermission.SALON_READ,
            OperatorPermission.SALON_CREATE, OperatorPermission.PLAN_READ, OperatorPermission.PLAN_CREATE)),

    OPERATOR_SUPPORT(Set.of(OperatorPermission.SESSION_MANAGE));

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
