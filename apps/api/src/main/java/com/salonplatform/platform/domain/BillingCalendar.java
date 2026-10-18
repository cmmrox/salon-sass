package com.salonplatform.platform.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;

/**
 * The owner's billing terms (decision 049; docs/architecture/operator-console.md, "Subscriptions,
 * invoices and entitlements") as pure rules over instants. Durations are exact: a trial ends exactly
 * 14 days after it starts, an invoice is due exactly 7 days after issue, and the reminder and
 * read-only boundaries are exact offsets from the due instant. Months are calendar months in the
 * salon's time zone, anchored to the trial-end local date and time: a 31st anchor falls on a shorter
 * month's last day, and the next month returns to the 31st. A local time that a clock change skips
 * moves forward by the gap; one that repeats takes the earlier instant (as {@code SalonTime}).
 */
public final class BillingCalendar {

    public static final Duration TRIAL = Duration.ofDays(14);
    public static final Duration DUE_AFTER_ISSUE = Duration.ofDays(7);
    public static final Duration SECOND_REMINDER = Duration.ofDays(3);
    public static final Duration THIRD_REMINDER = Duration.ofDays(10);
    public static final Duration READ_ONLY_AFTER_DUE = Duration.ofDays(14);

    /** A subscription's state (no unpaid invoice past due is {@code active}). */
    public enum State {
        TRIALING("trialing"), ACTIVE("active"), PAST_DUE("past_due"), READ_ONLY("read_only");

        private final String value;

        State(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static State of(String value) {
            for (State state : values()) {
                if (state.value.equals(value)) {
                    return state;
                }
            }
            throw new IllegalArgumentException("unknown subscription state");
        }
    }

    /** A reminder or notice to the Owner contact, by the boundary that triggers it. */
    public enum Reminder {
        DUE("due", Duration.ZERO), DUE_3("due_3", SECOND_REMINDER), DUE_10("due_10", THIRD_REMINDER);

        private final String kind;
        private final Duration afterDue;

        Reminder(String kind, Duration afterDue) {
            this.kind = kind;
            this.afterDue = afterDue;
        }

        public String kind() {
            return kind;
        }

        /** The instant this reminder is due for an invoice due at {@code due}. */
        public Instant at(Instant due) {
            return due.plus(afterDue);
        }
    }

    private BillingCalendar() {
    }

    public static Instant trialEnd(Instant start) {
        return start.plus(TRIAL);
    }

    /** The anchor of the monthly cycle: the trial-end instant as a local date and time in the zone. */
    public static LocalDateTime anchor(Instant trialEnd, ZoneId zone) {
        return trialEnd.atZone(zone).toLocalDateTime();
    }

    /**
     * The start of the period with this index (0 is the first, starting at trial end), always
     * computed from the anchor so that a clamped month does not move later months.
     */
    public static Instant periodStart(LocalDateTime anchor, ZoneId zone, int index) {
        if (index < 0) {
            throw new IllegalArgumentException("a period index is not negative");
        }
        return ZonedDateTime.ofLocal(anchor.plusMonths(index), zone, null).toInstant();
    }

    public static Instant due(Instant issued) {
        return issued.plus(DUE_AFTER_ISSUE);
    }

    public static Instant readOnlyAt(Instant due) {
        return due.plus(READ_ONLY_AFTER_DUE);
    }

    /**
     * The state a subscription is in at {@code now}, from the due instants of its unpaid invoices:
     * {@code read_only} once one is unpaid 14 days after its due instant, {@code past_due} once one is
     * unpaid at its due instant, otherwise {@code active}; {@code trialing} while no invoice has been
     * issued. Each boundary is inclusive: at the boundary instant the state has changed, one second
     * before it has not.
     */
    public static State stateAt(Instant now, int invoicesIssued, Collection<Instant> unpaidDue) {
        if (invoicesIssued == 0) {
            return State.TRIALING;
        }
        if (unpaidDue.stream().anyMatch(due -> !now.isBefore(readOnlyAt(due)))) {
            return State.READ_ONLY;
        }
        if (unpaidDue.stream().anyMatch(due -> !now.isBefore(due))) {
            return State.PAST_DUE;
        }
        return State.ACTIVE;
    }

    /** The reminders due for an unpaid invoice at {@code now}, in order. */
    public static List<Reminder> remindersDue(Instant now, Instant due) {
        return java.util.Arrays.stream(Reminder.values()).filter(reminder -> !now.isBefore(reminder.at(due))).toList();
    }
}
