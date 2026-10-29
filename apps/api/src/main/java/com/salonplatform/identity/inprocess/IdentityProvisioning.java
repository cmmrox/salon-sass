package com.salonplatform.identity.inprocess;

import java.time.Instant;
import java.util.UUID;

/**
 * Identity's provisioning contract for platform ({@code docs/architecture/operator-console.md}): the
 * identity rules platform needs for an operator or a salon's contact, a salon's staff-seat usage, and
 * the one bootstrap of a new salon's first Owner. Platform never writes identity's tables.
 *
 * <p>In-process only: {@link #bootstrapOwner} and {@link #activeStaffCount} join the caller's platform
 * transaction and never commit on their own. A new salon, its plan, its first Owner and its brand
 * commit or roll back together, so that no partly created salon can be resolved; a staff-seat count is
 * read in the same transaction as the plan move it guards. If identity is extracted, the bootstrap
 * becomes an event with a compensating action and the count a value platform keeps from identity's
 * events, not remote calls.
 */
public interface IdentityProvisioning {

    /** Identity's password rule: 15 to 128 code points and not a listed common password. */
    boolean acceptablePassword(String password);

    /**
     * Identity's email normalization: trimmed, lower case, a plausible address of at most 254
     * characters. Blank is null.
     *
     * @throws IllegalArgumentException when it is not an acceptable address
     */
    String normalizedEmail(String value);

    /**
     * Identity's Sri Lankan mobile normalization to {@code +947XXXXXXXX}. Blank is null.
     *
     * @throws IllegalArgumentException when it is not such a number
     */
    String normalizedMobile(String value);

    /**
     * Validates a first Owner with identity's rules and hashes the password, before any transaction
     * opens. Field names are prefixed (for example {@code owner.email}).
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 400 {@code bad_request} naming every
     *     failing field
     */
    PreparedOwner prepareOwner(String prefix, String name, String email, String password);

    /**
     * Inserts the prepared Owner of a salon created in the current platform transaction: active, role
     * Owner, no MFA secret and no session. The database refuses any other salon and a second bootstrap.
     *
     * @return the new Owner's staff id
     * @throws IllegalArgumentException when the owner was not produced by {@link #prepareOwner}, before
     *     anything is read or written
     * @throws IllegalStateException outside a platform transaction
     */
    UUID bootstrapOwner(UUID tenantId, PreparedOwner owner, Instant at);

    /**
     * The number of active staff accounts of a salon (its staff-seat usage), read through identity's
     * {@code identity_active_staff_count} function in the current platform transaction: a count and
     * nothing else of the salon's staff.
     *
     * @throws IllegalStateException outside a platform transaction
     */
    long activeStaffCount(UUID tenantId);
}
