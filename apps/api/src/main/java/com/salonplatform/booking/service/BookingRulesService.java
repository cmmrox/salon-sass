package com.salonplatform.booking.service;

import com.salonplatform.booking.BookingRules;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The salon's booking rules (F04-S04): public, and kept by its settings staff ({@code settings.read},
 * {@code settings.manage}). A save applies at once to new bookings and creates a new immutable version; nothing
 * is a draft. The branch is the salon's default branch (R1 has one; no screen names it).
 */
public interface BookingRulesService {

    /** The rules as a request sends them, validated here. Wrappers, so that a missing value is told apart. */
    record RulesInput(
            Integer slotIntervalMinutes,
            Integer minimumNoticeMinutes,
            Integer bookingHorizonDays,
            DepositInput deposit,
            Integer freeCancellationHours,
            RetentionInput lateCancellation,
            RetentionInput noShow,
            RescheduleInput reschedule) {}

    /** {@code none}, {@code percentage} with {@code percent} and {@code capMinor}, or {@code amount}. */
    record DepositInput(String kind, Integer percent, Long capMinor, Long amountMinor) {}

    /** {@code nothing}, {@code deposit}, or {@code share} with {@code percent}. */
    record RetentionInput(String kind, Integer percent) {}

    /** {@code limit} 0 (not online, no {@code untilHours}), 1, 2 or null (any number of times). */
    record RescheduleInput(Integer limit, Integer untilHours) {}

    /**
     * The rules as settings staff keep them.
     *
     * @param version the current version's number, which a save is made from: 0 before the first save
     * @param rules the current version, or null before the first save
     * @param currency the currency amounts are entered in: the current version's, or the salon's before the first
     *     save (LKR when it has none)
     */
    record RulesSettings(long version, BookingRules rules, String currency) {}

    /**
     * One version in the history.
     *
     * @param createdByLabel the author's email or mobile number as the staff list shows it; null when the account
     *     is not known
     * @param changedFields the rules it changed from the version before it
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

    /** The current rules, which anyone may read: empty before the first save. */
    Optional<BookingRules> publicRules();

    RulesSettings settings();

    /**
     * Saves the rules as a new version and makes it current. 400 naming each bad field, with nothing saved;
     * 409 {@code conflict} when {@code version} is not the current version, and {@code invalid_state} when the
     * rules equal the current version's. Recorded in the salon's audit trail with the version and the changed
     * rules.
     */
    RulesSettings save(Long version, RulesInput rules);

    /** The versions, newest first: page from 0, size 1 to 100. 400 on {@code page} otherwise. */
    History history(int page, int size);
}
