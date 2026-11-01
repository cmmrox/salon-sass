package com.salonplatform.booking;

import java.time.Instant;
import java.util.UUID;

/**
 * One version of a salon's booking rules: written once and never changed (decision 089). A booking keeps the
 * {@code versionId} it was made under, so a later version never rewrites its terms.
 *
 * @param versionId the version's id, which a booking stores
 * @param version its number for the branch, from 1; the newest is current
 * @param branchId the branch it applies to (in R1, the salon's default branch)
 * @param currency the ISO 4217 currency of its amounts: the salon's when it was saved
 * @param createdAt when it was saved
 * @param terms the rules
 */
public record BookingRules(
        UUID versionId, long version, UUID branchId, String currency, Instant createdAt, BookingTerms terms) {}
