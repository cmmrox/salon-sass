package com.salonplatform.catalogue.service;

import com.salonplatform.catalogue.TaxSettingsVersion;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The salon's tax settings (CAT-05, F05-S03): VAT and a service charge, both off until the salon says it is
 * registered for them. Public as customers see them; kept by its settings staff ({@code settings.read},
 * {@code settings.manage}, decision 085). A save applies at once to new bookings and sales and creates a new
 * immutable version; nothing is a draft. The branch is the salon's default branch (R1 has one; no screen names it).
 */
public interface TaxSettingsService {

    /** VAT as a request sends it, validated here. Wrappers, so that a missing value is told apart. */
    record VatInput(Boolean registered, String registrationNumber) {}

    /** The service charge as a request sends it, validated here. */
    record ServiceChargeInput(Boolean on, Integer percent, Boolean onProducts) {}

    /**
     * The tax this platform's configuration offers the salon's country: the names and rates a save copies.
     *
     * @param serviceChargeMaxPercent the highest service charge a salon may charge; the lowest is 1
     */
    record Configured(
            String vatName,
            int vatRatePercent,
            String registrationLabel,
            String serviceChargeName,
            int serviceChargeMaxPercent) {}

    /**
     * The settings as settings staff keep them.
     *
     * @param version the current version's number, which a save is made from: 0 before the first save
     * @param current the current version, or null before the first save
     */
    record Settings(long version, TaxSettingsVersion current, Configured configured) {}

    /**
     * One version in the history.
     *
     * @param createdByLabel the author's email or mobile number as the staff list shows it; null when the account is
     *     not known
     * @param changedFields the settings it changed from the version before it
     */
    record HistoryEntry(
            UUID versionId,
            long version,
            Instant createdAt,
            UUID createdBy,
            String createdByLabel,
            List<String> changedFields) {}

    /** A page of the history, newest first. */
    record History(List<HistoryEntry> items, int page, int size, long total) {}

    /** The current settings, which anyone may read: empty before the first save. */
    Optional<TaxSettingsVersion> publicTax();

    Settings settings();

    /**
     * Saves the settings as a new version, with the configured names and VAT rate copied into it, and makes it
     * current. 400 naming each bad field, with nothing saved; 409 {@code conflict} when {@code version} is not the
     * current version, and {@code invalid_state} when the new version would equal the current one. Recorded in the
     * salon's audit trail with the values before and after.
     */
    Settings save(Long version, VatInput vat, ServiceChargeInput serviceCharge);

    /** The versions, newest first: page 0 to 100000, size 1 to 100. 400 on {@code page} otherwise. */
    History history(int page, int size);
}
