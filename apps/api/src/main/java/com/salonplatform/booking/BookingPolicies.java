package com.salonplatform.booking;

import java.util.Optional;
import java.util.UUID;

/**
 * The salon's booking rules, as F07 (availability: interval, notice, horizon), F08 (deposits) and F09
 * (cancellation, no-show and rescheduling) read them (decision 089). This is the only way another module reads
 * them.
 *
 * <p>Every save of the rules creates a new immutable version, and the newest is current. A booking stores the
 * {@link BookingRules#versionId()} it was made under and later reads that version with {@link #version}, so a
 * change the salon makes afterwards never rewrites the terms an existing booking was made under.
 *
 * <p>The salon is the request's, never a parameter (docs/engineering/backend.md, "Provided interfaces"). Each
 * call opens its own read-only tenant transaction and does not join the caller's.
 */
public interface BookingPolicies {

    /**
     * The current rules of the salon's default branch (R1 has one branch per salon): its newest version.
     * Empty until the salon first saves its rules; a caller then has no rules to apply and must say so rather
     * than assume any.
     *
     * @throws com.salonplatform.shared.tenancy.TenantNotFoundException when the request names no salon
     */
    Optional<BookingRules> current();

    /**
     * A version of the current salon's rules by its id, current or not, with exactly the values it was saved
     * with. Another salon's version id is empty, exactly as an id that names no version: the two cannot be told
     * apart.
     *
     * @throws com.salonplatform.shared.tenancy.TenantNotFoundException when the request names no salon
     */
    Optional<BookingRules> version(UUID versionId);

    /**
     * The deposit a booking of one service asks for under these rules: the service's own rule when it sets one
     * (the per-service override of BKG-09, set on the service in F05), otherwise the rules' deposit. An override
     * of {@link DepositRule.None} means that service asks for no deposit even when the salon's rules do. Its
     * amounts are read in the rules' {@link BookingRules#currency()}. Reads nothing; the rules are those the
     * booking is made or kept under.
     */
    DepositRule depositFor(BookingRules rules, Optional<DepositRule> serviceRule);
}
