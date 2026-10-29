package com.salonplatform.identity;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Who the current request acts as: a staff member, a customer, or an operator in a support session.
 * Business modules enforce {@link #scope()} on their data.
 *
 * @param id the staff account, customer account or support session
 * @param kind {@code staff}, {@code customer} or {@code support}
 * @param role the staff role; null for a customer or a support session
 * @param permissions what the principal may do
 * @param scope the data the principal may reach: {@code salon}, or {@code own} for its own records only
 * @param staffId the staff account; null for a customer or a support session
 * @param mfaEnabled whether the staff account has two-step verification enrolled
 * @param expiresAt when a support session ends; null for every other kind
 */
public record Principal(
        UUID id,
        String kind,
        String role,
        Set<String> permissions,
        String scope,
        UUID staffId,
        boolean mfaEnabled,
        Instant expiresAt) {

    /** A staff or customer principal, whose access ends with its session rather than at a set time. */
    public Principal(
            UUID id,
            String kind,
            String role,
            Set<String> permissions,
            String scope,
            UUID staffId,
            boolean mfaEnabled) {
        this(id, kind, role, permissions, scope, staffId, mfaEnabled, null);
    }
}
