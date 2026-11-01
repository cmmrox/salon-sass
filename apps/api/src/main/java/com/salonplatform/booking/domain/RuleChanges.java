package com.salonplatform.booking.domain;

import com.salonplatform.booking.BookingTerms;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Which rules a save changes, named as the API names them, in the form's order. A branch's first version
 * changes every rule; a save that changes none creates no version (F04-S04 AC-5).
 */
public final class RuleChanges {

    private RuleChanges() {}

    /** Every rule, in the form's order. */
    public static final List<String> FIELDS = List.of(
            "slotIntervalMinutes",
            "minimumNoticeMinutes",
            "bookingHorizonDays",
            "deposit",
            "freeCancellationHours",
            "lateCancellation",
            "noShow",
            "reschedule");

    /** The rules {@code after} changes from {@code before}; every rule when there is no {@code before}. */
    public static List<String> between(BookingTerms before, BookingTerms after) {
        if (before == null) {
            return FIELDS;
        }
        List<String> changed = new ArrayList<>();
        add(changed, "slotIntervalMinutes", before.slotIntervalMinutes(), after.slotIntervalMinutes());
        add(changed, "minimumNoticeMinutes", before.minimumNoticeMinutes(), after.minimumNoticeMinutes());
        add(changed, "bookingHorizonDays", before.bookingHorizonDays(), after.bookingHorizonDays());
        add(changed, "deposit", before.deposit(), after.deposit());
        add(changed, "freeCancellationHours", before.freeCancellationHours(), after.freeCancellationHours());
        add(changed, "lateCancellation", before.lateCancellation(), after.lateCancellation());
        add(changed, "noShow", before.noShow(), after.noShow());
        add(changed, "reschedule", before.reschedule(), after.reschedule());
        return List.copyOf(changed);
    }

    private static void add(List<String> changed, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changed.add(field);
        }
    }
}
