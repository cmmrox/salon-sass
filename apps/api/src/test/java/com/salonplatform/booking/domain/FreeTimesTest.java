package com.salonplatform.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.booking.Availability.Audience;
import com.salonplatform.booking.Availability.Empty;
import com.salonplatform.booking.Availability.FreeTime;
import com.salonplatform.booking.NoPreference;
import com.salonplatform.catalogue.SegmentKind;
import com.salonplatform.catalogue.ServiceSegment;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The availability calculation (F07-S02 AC-1 to AC-4), every input and edge, with a fixed clock. */
class FreeTimesTest {

    private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");
    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final LocalDate DAY = LocalDate.of(2030, 6, 17);
    /** The day before, so that every time on {@link #DAY} is in the future. */
    private static final Instant EARLIER = at(DAY.minusDays(1), "12:00");

    private static final UUID ASHA = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID BIMAL = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private static final UUID CHAMI = UUID.fromString("00000000-0000-4000-8000-00000000000c");

    /** 45 minutes and a 15-minute buffer: an hour of the professional's time. */
    private static final List<ServiceSegment> CUT = List.of(active(45), buffer(15));

    private static ServiceSegment active(int minutes) {
        return new ServiceSegment(SegmentKind.ACTIVE, minutes);
    }

    private static ServiceSegment buffer(int minutes) {
        return new ServiceSegment(SegmentKind.BUFFER, minutes);
    }

    private static ServiceSegment processing(int minutes) {
        return new ServiceSegment(SegmentKind.PROCESSING, minutes);
    }

    private static Instant at(LocalDate date, String time) {
        return ZonedDateTime.of(date, LocalTime.parse(time), COLOMBO).toInstant();
    }

    private static FreeTimes.Window window(String from, String to) {
        return new FreeTimes.Window(LocalTime.parse(from), LocalTime.parse(to));
    }

    private static Occupation.Range busy(String from, String to) {
        return new Occupation.Range(at(DAY, from), at(DAY, to));
    }

    private static FreeTimes.Professional person(
            UUID id, List<FreeTimes.Window> windows, List<Occupation.Range> occupied) {
        return new FreeTimes.Professional(id, "P-" + id.toString().substring(35), windows, occupied);
    }

    private static FreeTimes.Day day(
            FreeTimes.Window opening, List<ServiceSegment> segments, int interval, FreeTimes.Professional... team) {
        return new FreeTimes.Day(DAY, COLOMBO, opening, List.of(team), segments, interval, NoPreference.LEAST_BOOKED);
    }

    private static FreeTimes.Audiences staff(Instant now) {
        return new FreeTimes.Audiences(Audience.STAFF, now, 60, 14);
    }

    private static List<String> times(FreeTimes.Result result) {
        return result.times().stream().map(time -> time.time().toString()).toList();
    }

    private static List<UUID> who(FreeTimes.Result result) {
        return result.times().stream().map(FreeTime::memberId).toList();
    }

    @Nested
    class Windows {

        @Test
        void startsAreOnTheIntervalFromMidnightAndTheWholeBookingFitsUpToClosing() {
            FreeTimes.Result result = FreeTimes.answer(
                    day(window("09:00", "12:00"), CUT, 15, person(ASHA, List.of(window("09:00", "12:00")), List.of())),
                    staff(EARLIER));

            assertThat(times(result))
                    .containsExactly("09:00", "09:15", "09:30", "09:45", "10:00", "10:15", "10:30", "10:45", "11:00");
            assertThat(result.reason()).isNull();
            assertThat(result.times().getFirst().startsAt()).isEqualTo(at(DAY, "09:00"));
            assertThat(result.times().getLast().startsAt()).isEqualTo(at(DAY, "11:00"));
        }

        @Test
        void eachIntervalCountsFromMidnightNotFromOpening() {
            FreeTimes.Professional asha = person(ASHA, List.of(window("09:10", "11:00")), List.of());
            FreeTimes.Window opening = window("09:10", "11:00");
            List<ServiceSegment> short30 = List.of(active(30));

            assertThat(times(FreeTimes.answer(day(opening, short30, 20, asha), staff(EARLIER))))
                    .containsExactly("09:20", "09:40", "10:00", "10:20");
            assertThat(times(FreeTimes.answer(day(opening, short30, 30, asha), staff(EARLIER))))
                    .containsExactly("09:30", "10:00", "10:30");
            assertThat(times(FreeTimes.answer(day(opening, short30, 10, asha), staff(EARLIER))))
                    .containsExactly("09:10", "09:20", "09:30", "09:40", "09:50", "10:00", "10:10", "10:20", "10:30");
        }

        @Test
        void theProfessionalsWindowAndTheOpeningHoursMustBothHoldTheWholeBooking() {
            FreeTimes.Result narrower = FreeTimes.answer(
                    day(window("09:00", "17:00"), CUT, 15, person(ASHA, List.of(window("10:00", "11:30")), List.of())),
                    staff(EARLIER));
            assertThat(times(narrower)).containsExactly("10:00", "10:15", "10:30");

            // Their hours go beyond the salon's (decision 102(c)): only what both allow.
            FreeTimes.Result wider = FreeTimes.answer(
                    day(window("10:00", "11:30"), CUT, 15, person(ASHA, List.of(window("08:00", "20:00")), List.of())),
                    staff(EARLIER));
            assertThat(times(wider)).containsExactly("10:00", "10:15", "10:30");
        }

        @Test
        void aBreakSplitsTheDayAndNoBookingRunsIntoIt() {
            FreeTimes.Result result = FreeTimes.answer(
                    day(
                            window("09:00", "15:00"),
                            CUT,
                            15,
                            person(ASHA, List.of(window("09:00", "12:00"), window("13:00", "15:00")), List.of())),
                    staff(EARLIER));

            assertThat(times(result))
                    .contains("11:00", "13:00", "14:00")
                    .doesNotContain("11:15", "11:45", "12:00", "12:15", "12:45", "14:15");
        }
    }

    @Nested
    class Occupied {

        @Test
        void theBufferMayNotReachAnotherBookingButMayTouchIt() {
            FreeTimes.Professional asha =
                    person(ASHA, List.of(window("09:00", "13:00")), List.of(busy("10:00", "11:00")));

            FreeTimes.Result withBuffer =
                    FreeTimes.answer(day(window("09:00", "13:00"), CUT, 15, asha), staff(EARLIER));
            assertThat(times(withBuffer)).containsExactly("09:00", "11:00", "11:15", "11:30", "11:45", "12:00");

            FreeTimes.Result noBuffer =
                    FreeTimes.answer(day(window("09:00", "13:00"), List.of(active(45)), 15, asha), staff(EARLIER));
            assertThat(times(noBuffer)).contains("09:00", "09:15").doesNotContain("09:30", "10:00", "10:45");
        }

        @Test
        void aProcessingSegmentOccupiesNobodySoItMayLieOverAnotherBooking() {
            FreeTimes.Professional asha =
                    person(ASHA, List.of(window("10:00", "12:00")), List.of(busy("10:30", "11:00")));
            List<ServiceSegment> colour = List.of(active(30), processing(30), active(15));

            FreeTimes.Result result = FreeTimes.answer(day(window("10:00", "12:00"), colour, 15, asha), staff(EARLIER));

            assertThat(times(result)).startsWith("10:00").doesNotContain("10:15", "10:30");
        }

        @Test
        void oneProfessionalsBookingsNeverTakeAnothersTime() {
            FreeTimes.Professional asha =
                    person(ASHA, List.of(window("09:00", "10:00")), List.of(busy("09:00", "10:00")));
            FreeTimes.Professional bimal = person(BIMAL, List.of(window("09:00", "10:00")), List.of());

            FreeTimes.Result result = FreeTimes.answer(
                    day(window("09:00", "10:00"), List.of(active(30)), 15, asha, bimal), staff(EARLIER));

            assertThat(times(result)).containsExactly("09:00", "09:15", "09:30");
            assertThat(who(result)).containsOnly(BIMAL);
        }
    }

    @Nested
    class Audiences {

        @Test
        void forStaffTodaysTimesStartAfterTheCurrentTimeWithNoNotice() {
            FreeTimes.Day today =
                    day(window("09:00", "12:00"), CUT, 15, person(ASHA, List.of(window("09:00", "12:00")), List.of()));

            assertThat(times(FreeTimes.answer(today, staff(at(DAY, "10:07")))))
                    .containsExactly("10:15", "10:30", "10:45", "11:00");
            assertThat(times(FreeTimes.answer(today, staff(at(DAY, "10:15")))))
                    .containsExactly("10:30", "10:45", "11:00");
            assertThat(FreeTimes.answer(today, staff(at(DAY, "11:00"))).reason())
                    .isEqualTo(Empty.FULLY_BOOKED);
        }

        @Test
        void forStaffTheHorizonDoesNotApply() {
            FreeTimes.Day farAhead =
                    day(window("09:00", "10:00"), CUT, 15, person(ASHA, List.of(window("09:00", "10:00")), List.of()));

            assertThat(times(FreeTimes.answer(
                            farAhead,
                            staff(DAY.minusDays(300).atStartOfDay(COLOMBO).toInstant()))))
                    .containsExactly("09:00");
        }

        @Test
        void forCustomersTheMinimumNoticeApplies() {
            FreeTimes.Day today =
                    day(window("09:00", "13:00"), CUT, 15, person(ASHA, List.of(window("09:00", "13:00")), List.of()));

            FreeTimes.Result hourAhead =
                    FreeTimes.answer(today, new FreeTimes.Audiences(Audience.CUSTOMER, at(DAY, "10:07"), 60, 14));
            assertThat(times(hourAhead)).containsExactly("11:15", "11:30", "11:45", "12:00");

            FreeTimes.Result exactlyAnHour =
                    FreeTimes.answer(today, new FreeTimes.Audiences(Audience.CUSTOMER, at(DAY, "10:00"), 60, 14));
            assertThat(times(exactlyAnHour)).startsWith("11:00");

            FreeTimes.Result anyTime =
                    FreeTimes.answer(today, new FreeTimes.Audiences(Audience.CUSTOMER, at(DAY, "10:00"), 0, 14));
            assertThat(times(anyTime)).startsWith("10:15");
        }

        @Test
        void forCustomersADateBeyondTheHorizonOrBeforeTodayHasNoTimes() {
            FreeTimes.Day theDay =
                    day(window("09:00", "10:00"), CUT, 15, person(ASHA, List.of(window("09:00", "10:00")), List.of()));

            Instant fourteenDaysBefore = at(DAY.minusDays(14), "09:00");
            assertThat(times(FreeTimes.answer(
                            theDay, new FreeTimes.Audiences(Audience.CUSTOMER, fourteenDaysBefore, 0, 14))))
                    .containsExactly("09:00");

            Instant fifteenDaysBefore = at(DAY.minusDays(15), "09:00");
            FreeTimes.Result beyond =
                    FreeTimes.answer(theDay, new FreeTimes.Audiences(Audience.CUSTOMER, fifteenDaysBefore, 0, 14));
            assertThat(beyond.times()).isEmpty();
            assertThat(beyond.reason()).isEqualTo(Empty.OUTSIDE_BOOKING_WINDOW);

            FreeTimes.Result past = FreeTimes.answer(
                    theDay, new FreeTimes.Audiences(Audience.CUSTOMER, at(DAY.plusDays(1), "08:00"), 0, 14));
            assertThat(past.reason()).isEqualTo(Empty.OUTSIDE_BOOKING_WINDOW);
        }
    }

    @Nested
    class Reasons {

        @Test
        void aClosedDateIsClosed() {
            FreeTimes.Result result = FreeTimes.answer(
                    day(null, CUT, 15, person(ASHA, List.of(window("09:00", "17:00")), List.of())), staff(EARLIER));

            assertThat(result.times()).isEmpty();
            assertThat(result.reason()).isEqualTo(Empty.CLOSED);
        }

        @Test
        void nobodyEligibleIsNobodyPerforms() {
            assertThat(FreeTimes.answer(day(window("09:00", "17:00"), CUT, 15), staff(EARLIER))
                            .reason())
                    .isEqualTo(Empty.NOBODY_PERFORMS);
        }

        @Test
        void nobodyWithAWindowIsNotWorking() {
            FreeTimes.Result result = FreeTimes.answer(
                    day(
                            window("09:00", "17:00"),
                            CUT,
                            15,
                            person(ASHA, List.of(), List.of()),
                            person(BIMAL, List.of(), List.of())),
                    staff(EARLIER));

            assertThat(result.reason()).isEqualTo(Empty.NOT_WORKING);
        }

        @Test
        void someoneWorkingWithNothingFreeIsFullyBooked() {
            FreeTimes.Result booked = FreeTimes.answer(
                    day(
                            window("09:00", "11:00"),
                            CUT,
                            15,
                            person(ASHA, List.of(window("09:00", "11:00")), List.of(busy("09:00", "11:00"))),
                            person(BIMAL, List.of(), List.of())),
                    staff(EARLIER));
            assertThat(booked.reason()).isEqualTo(Empty.FULLY_BOOKED);

            FreeTimes.Result tooShort = FreeTimes.answer(
                    day(window("09:00", "11:00"), CUT, 15, person(ASHA, List.of(window("09:00", "09:45")), List.of())),
                    staff(EARLIER));
            assertThat(tooShort.reason()).isEqualTo(Empty.FULLY_BOOKED);
        }
    }

    @Nested
    class NoPreferenceStrategy {

        private final FreeTimes.Window hours = window("09:00", "18:00");

        private FreeTimes.Day team(NoPreference strategy, FreeTimes.Professional... people) {
            return new FreeTimes.Day(DAY, COLOMBO, hours, List.of(people), List.of(active(60)), 60, strategy);
        }

        @Test
        void leastBookedGivesEachTimeToWhoeverHasTheFewestMinutesThatDay() {
            FreeTimes.Professional asha = person(ASHA, List.of(hours), List.of(busy("16:00", "18:00")));
            FreeTimes.Professional bimal = person(BIMAL, List.of(hours), List.of(busy("09:00", "10:00")));

            FreeTimes.Result result = FreeTimes.answer(team(NoPreference.LEAST_BOOKED, asha, bimal), staff(EARLIER));

            assertThat(times(result))
                    .containsExactly("09:00", "10:00", "11:00", "12:00", "13:00", "14:00", "15:00", "16:00", "17:00");
            assertThat(who(result)).containsExactly(ASHA, BIMAL, BIMAL, BIMAL, BIMAL, BIMAL, BIMAL, BIMAL, BIMAL);
        }

        @Test
        void mostBookedGivesEachTimeToWhoeverHasTheMostThatDayAndTheNextWhenTheyAreBusy() {
            FreeTimes.Professional asha = person(ASHA, List.of(hours), List.of(busy("16:00", "18:00")));
            FreeTimes.Professional bimal = person(BIMAL, List.of(hours), List.of(busy("09:00", "10:00")));

            FreeTimes.Result result = FreeTimes.answer(team(NoPreference.MOST_BOOKED, asha, bimal), staff(EARLIER));

            assertThat(who(result)).containsExactly(ASHA, ASHA, ASHA, ASHA, ASHA, ASHA, ASHA, BIMAL, BIMAL);
        }

        @Test
        void tiesGoToTheOneTheTeamPageListsFirst() {
            FreeTimes.Professional chami = person(CHAMI, List.of(hours), List.of());
            FreeTimes.Professional asha = person(ASHA, List.of(hours), List.of());
            FreeTimes.Professional bimal = person(BIMAL, List.of(hours), List.of(busy("09:00", "10:00")));

            assertThat(who(FreeTimes.answer(team(NoPreference.LEAST_BOOKED, chami, asha, bimal), staff(EARLIER))))
                    .containsOnly(CHAMI);
            assertThat(who(FreeTimes.answer(team(NoPreference.MOST_BOOKED, chami, asha, bimal), staff(EARLIER))))
                    .startsWith(CHAMI)
                    .containsOnly(BIMAL, CHAMI)
                    .element(1)
                    .isEqualTo(BIMAL);
        }

        @Test
        void onlyTimeWithinTheLocalDateCounts() {
            // Asha's late booking the evening before runs an hour into the day; Bimal has 90 minutes on the day itself.
            FreeTimes.Professional asha = person(
                    ASHA,
                    List.of(hours),
                    List.of(new Occupation.Range(at(DAY.minusDays(1), "22:00"), at(DAY, "01:00"))));
            FreeTimes.Professional bimal = person(BIMAL, List.of(hours), List.of(busy("16:30", "18:00")));

            FreeTimes.Result least = FreeTimes.answer(team(NoPreference.LEAST_BOOKED, bimal, asha), staff(EARLIER));
            assertThat(who(least).getFirst()).isEqualTo(ASHA);
        }

        @Test
        void eachTimeIsOfferedOnce() {
            FreeTimes.Result result = FreeTimes.answer(
                    team(
                            NoPreference.LEAST_BOOKED,
                            person(ASHA, List.of(hours), List.of()),
                            person(BIMAL, List.of(hours), List.of()),
                            person(CHAMI, List.of(hours), List.of())),
                    staff(EARLIER));

            assertThat(times(result)).doesNotHaveDuplicates().hasSize(9);
        }
    }

    @Nested
    class ClockChanges {

        private FreeTimes.Day london(LocalDate date) {
            FreeTimes.Window night = window("00:00", "03:00");
            return new FreeTimes.Day(
                    date,
                    LONDON,
                    night,
                    List.of(person(ASHA, List.of(night), List.of())),
                    List.of(active(30)),
                    30,
                    NoPreference.LEAST_BOOKED);
        }

        @Test
        void theLocalTimesTheClocksSkipAreNotOffered() {
            LocalDate springForward = LocalDate.of(2030, 3, 31);
            FreeTimes.Result result = FreeTimes.answer(
                    london(springForward),
                    staff(springForward.minusDays(1).atStartOfDay(LONDON).toInstant()));

            assertThat(times(result)).containsExactly("00:00", "00:30", "02:00", "02:30");
            assertThat(result.times().stream().map(FreeTime::startsAt))
                    .containsExactly(
                            Instant.parse("2030-03-31T00:00:00Z"),
                            Instant.parse("2030-03-31T00:30:00Z"),
                            Instant.parse("2030-03-31T01:00:00Z"),
                            Instant.parse("2030-03-31T01:30:00Z"));
        }

        @Test
        void inTheHourThatRepeatsEachTimeIsOfferedOnceAtItsEarlierInstant() {
            LocalDate fallBack = LocalDate.of(2030, 10, 27);
            FreeTimes.Result result = FreeTimes.answer(
                    london(fallBack),
                    staff(fallBack.minusDays(1).atStartOfDay(LONDON).toInstant()));

            assertThat(times(result)).containsExactly("00:00", "00:30", "01:00", "01:30", "02:00", "02:30");
            assertThat(result.times().get(2).startsAt()).isEqualTo(Instant.parse("2030-10-27T00:00:00Z"));
            assertThat(result.times().get(4).startsAt()).isEqualTo(Instant.parse("2030-10-27T02:00:00Z"));
        }
    }

    @Nested
    class Assigning {

        private final FreeTimes.Window hours = window("09:00", "12:00");

        private FreeTimes.Day team(FreeTimes.Window opening, NoPreference strategy, FreeTimes.Professional... people) {
            return new FreeTimes.Day(DAY, COLOMBO, opening, List.of(people), CUT, 15, strategy);
        }

        @Test
        void theFreeAreListedInTheStrategysOrder() {
            FreeTimes.Professional asha = person(ASHA, List.of(hours), List.of(busy("11:00", "12:00")));
            FreeTimes.Professional bimal = person(BIMAL, List.of(hours), List.of());
            FreeTimes.Professional chami = person(CHAMI, List.of(hours), List.of(busy("09:30", "10:30")));

            assertThat(FreeTimes.assign(team(hours, NoPreference.LEAST_BOOKED, asha, bimal, chami), at(DAY, "10:00")))
                    .isEqualTo(new FreeTimes.Assignment.Candidates(List.of(bimal, asha)));
            assertThat(FreeTimes.assign(team(hours, NoPreference.MOST_BOOKED, asha, bimal, chami), at(DAY, "10:00")))
                    .isEqualTo(new FreeTimes.Assignment.Candidates(List.of(asha, bimal)));
        }

        @Test
        void everyoneWorkingButBusyLeavesNoCandidate() {
            FreeTimes.Professional asha = person(ASHA, List.of(hours), List.of(busy("09:00", "12:00")));

            assertThat(FreeTimes.assign(team(hours, NoPreference.LEAST_BOOKED, asha), at(DAY, "10:00")))
                    .isEqualTo(new FreeTimes.Assignment.Candidates(List.of()));
        }

        @Test
        void aTimeNobodyCouldTakeSaysWhy() {
            FreeTimes.Professional asha = person(ASHA, List.of(window("09:00", "10:00")), List.of());

            assertThat(FreeTimes.assign(team(null, NoPreference.LEAST_BOOKED, asha), at(DAY, "09:00")))
                    .isInstanceOf(FreeTimes.Assignment.Unavailable.class);
            assertThat(FreeTimes.assign(team(hours, NoPreference.LEAST_BOOKED, asha), at(DAY, "11:30")))
                    .isEqualTo(new FreeTimes.Assignment.Unavailable(
                            "The booking falls outside the salon's opening hours."));
            assertThat(FreeTimes.assign(team(hours, NoPreference.LEAST_BOOKED), at(DAY, "09:00")))
                    .isEqualTo(new FreeTimes.Assignment.Unavailable("Nobody on the team performs this service."));
            assertThat(FreeTimes.assign(team(hours, NoPreference.LEAST_BOOKED, asha), at(DAY, "10:00")))
                    .isEqualTo(new FreeTimes.Assignment.Unavailable(
                            "Nobody who performs this service works at that time."));
        }
    }
}
