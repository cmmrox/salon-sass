package com.salonplatform.catalogue;

import java.time.Instant;
import java.util.UUID;

/**
 * One version of a salon's tax settings (CAT-05, decision 095): written once by a save and never changed, the newest
 * current. It carries the VAT name and rate and the service charge's name as configuration gave them when it was
 * saved, so a later change of configuration never rewrites it. A booking or a sale keeps the {@code versionId} it was
 * made under and reads it again with {@link TaxSettings#version}.
 *
 * @param versionId the version's id, which a booking or a sale stores
 * @param version its number for the branch, from 1; the newest is current
 * @param branchId the branch it applies to (in R1, the salon's default branch)
 * @param createdAt when it was saved
 * @param vat the VAT as saved
 * @param serviceCharge the service charge as saved
 */
public record TaxSettingsVersion(
        UUID versionId, long version, UUID branchId, Instant createdAt, Vat vat, ServiceCharge serviceCharge) {

    /**
     * VAT: charged only when the salon is registered for it (decision 092).
     *
     * @param name what it is called, as configured when the version was saved ("VAT")
     * @param ratePercent its rate as configured when the version was saved, a whole percentage from 0 to 100
     * @param registered whether the salon is registered: VAT is charged only then
     * @param registrationNumber the salon's registration number while registered, for receipts (F10); null otherwise.
     *     Never shown to customers
     */
    public record Vat(String name, int ratePercent, boolean registered, String registrationNumber) {}

    /**
     * The service charge: charged on services, and on products too when {@code onProducts}.
     *
     * @param name what it is called, as configured when the version was saved ("Service charge")
     * @param on whether the salon charges it
     * @param percent its rate while on, a whole percentage from 1 to the configured maximum; 0 while off
     * @param onProducts whether it is charged on products too; false while off
     */
    public record ServiceCharge(String name, boolean on, int percent, boolean onProducts) {}
}
