package com.salonplatform.platform.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.platform.domain.BillingCalendar.Reminder;
import com.salonplatform.platform.domain.BillingCalendar.State;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The owner's billing terms as pure rules (decision 049), each boundary one second either side. */
class BillingCalendarTest {

    private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    @Test
    void theTrialEndsExactlyFourteenDaysAfterItsStart() {
        Instant start = Instant.parse("2026-10-18T09:15:42.123Z");
        assertThat(BillingCalendar.trialEnd(start)).isEqualTo(Instant.parse("2026-11-01T09:15:42.123Z"));
    }

    @Test
    void aThirtyFirstAnchorFallsOnTheLastDayOfShorterMonthsAndReturnsToTheThirtyFirst() {
        LocalDateTime anchor = LocalDateTime.parse("2027-01-31T10:00:00");
        List<String> starts = java.util.stream.IntStream.range(0, 4)
                .mapToObj(n -> BillingCalendar.periodStart(anchor, COLOMBO, n)
                        .atZone(COLOMBO)
                        .toLocalDate()
                        .toString())
                .toList();
        assertThat(starts).containsExactly("2027-01-31", "2027-02-28", "2027-03-31", "2027-04-30");
        LocalDateTime leap = LocalDateTime.parse("2028-01-31T10:00:00");
        assertThat(BillingCalendar.periodStart(leap, COLOMBO, 1).atZone(COLOMBO).toLocalDate())
                .hasToString("2028-02-29");
    }

    @Test
    void monthsAreCalendarMonthsInTheSalonsZoneAcrossAClockChange() {
        // 10:00 London on 15 March (GMT) then 15 April (BST): one hour less than a whole number of days apart.
        LocalDateTime anchor = LocalDateTime.parse("2027-03-15T10:00:00");
        assertThat(BillingCalendar.periodStart(anchor, LONDON, 0)).isEqualTo(Instant.parse("2027-03-15T10:00:00Z"));
        assertThat(BillingCalendar.periodStart(anchor, LONDON, 1)).isEqualTo(Instant.parse("2027-04-15T09:00:00Z"));
        // A local time the change skips moves forward by the gap.
        LocalDateTime skipped = LocalDateTime.parse("2027-02-28T01:30:00");
        assertThat(BillingCalendar.periodStart(skipped, LONDON, 1)).isEqualTo(Instant.parse("2027-03-28T01:30:00Z"));
    }

    @Test
    void anInvoiceIsDueSevenDaysAfterIssueAndReadOnlyFollowsFourteenDaysAfterDue() {
        Instant issued = Instant.parse("2026-11-01T09:00:00Z");
        Instant due = BillingCalendar.due(issued);
        assertThat(due).isEqualTo(Instant.parse("2026-11-08T09:00:00Z"));
        assertThat(BillingCalendar.readOnlyAt(due)).isEqualTo(Instant.parse("2026-11-22T09:00:00Z"));
    }

    @Test
    void theStateChangesAtEachBoundaryAndNotOneSecondBefore() {
        Instant due = Instant.parse("2026-11-08T09:00:00Z");
        assertThat(BillingCalendar.stateAt(due.minusSeconds(1), 0, List.of())).isEqualTo(State.TRIALING);
        assertThat(BillingCalendar.stateAt(due.minusSeconds(1), 1, List.of(due)))
                .isEqualTo(State.ACTIVE);
        assertThat(BillingCalendar.stateAt(due, 1, List.of(due))).isEqualTo(State.PAST_DUE);
        Instant readOnly = BillingCalendar.readOnlyAt(due);
        assertThat(BillingCalendar.stateAt(readOnly.minusSeconds(1), 1, List.of(due)))
                .isEqualTo(State.PAST_DUE);
        assertThat(BillingCalendar.stateAt(readOnly, 1, List.of(due))).isEqualTo(State.READ_ONLY);
        assertThat(BillingCalendar.stateAt(readOnly, 2, List.of())).isEqualTo(State.ACTIVE);
        // One of two overdue invoices still unpaid keeps the oldest boundary.
        assertThat(BillingCalendar.stateAt(readOnly, 2, List.of(due.plusSeconds(86400 * 30))))
                .isEqualTo(State.ACTIVE);
    }

    @Test
    void remindersFallOnTheDueDateAndThreeAndTenDaysAfterIt() {
        Instant due = Instant.parse("2026-11-08T09:00:00Z");
        assertThat(BillingCalendar.remindersDue(due.minusSeconds(1), due)).isEmpty();
        assertThat(BillingCalendar.remindersDue(due, due)).containsExactly(Reminder.DUE);
        assertThat(BillingCalendar.remindersDue(due.plusSeconds(3 * 86400 - 1), due))
                .containsExactly(Reminder.DUE);
        assertThat(BillingCalendar.remindersDue(due.plusSeconds(3 * 86400), due))
                .containsExactly(Reminder.DUE, Reminder.DUE_3);
        assertThat(BillingCalendar.remindersDue(due.plusSeconds(10 * 86400 - 1), due))
                .hasSize(2);
        assertThat(BillingCalendar.remindersDue(due.plusSeconds(10 * 86400), due))
                .containsExactly(Reminder.DUE, Reminder.DUE_3, Reminder.DUE_10);
    }

    @Test
    void invoiceNumbersAndPlainAmountsAreWrittenInTheirFixedForms() {
        assertThat(BillingTerms.invoiceNumber(1)).isEqualTo("INV-000001");
        assertThat(BillingTerms.invoiceNumber(1234567)).isEqualTo("INV-1234567");
        assertThat(BillingTerms.plainAmount(250005, "LKR")).isEqualTo("LKR 2,500.05");
        assertThat(BillingTerms.validAmount(100_000_000L)).isTrue();
        assertThat(BillingTerms.validAmount(100_000_001L)).isFalse();
        assertThat(BillingTerms.validAmount(-1L)).isFalse();
    }
}
