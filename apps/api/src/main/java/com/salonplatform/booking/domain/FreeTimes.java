package com.salonplatform.booking.domain;

import com.salonplatform.booking.Availability.Audience;
import com.salonplatform.booking.Availability.Empty;
import com.salonplatform.booking.Availability.FreeTime;
import com.salonplatform.booking.NoPreference;
import com.salonplatform.catalogue.ServiceSegment;
import com.salonplatform.shared.time.SalonTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The availability calculation (BKG-01, BKG-03, F07-S02): given one local date's opening hours, each eligible
 * professional's working windows and occupied time, the service's segments, the slot interval, the clock and the
 * audience, the times a booking could take and who would take each. Pure: everything it reads is passed in, so the same
 * answer is given for the same inputs, and the rules are unit tested with a fixed clock.
 *
 * <p>A start is offered for a professional when the whole booking, start to the end of its last segment, lies inside the
 * opening hours and inside one of their windows, and none of the ranges it occupies (active and buffer; processing
 * occupies nobody) overlaps any of theirs. Ranges are half-open, so a booking may end exactly as another starts, or as
 * the salon closes. Local times become instants through {@link SalonTime}: a local start the clocks skip is not offered,
 * and in the hour that repeats a start is offered once, at its earlier instant.
 */
public final class FreeTimes {

    private FreeTimes() {}

    /** A stretch of local time on the date: {@code to} itself is outside it. */
    public record Window(LocalTime from, LocalTime to) {}

    /**
     * An eligible professional as the calculation reads them.
     *
     * @param windows when they can be booked on the date: empty when they do not work
     * @param occupied the ranges their live bookings and unexpired holds occupy, on or around the date
     */
    public record Professional(UUID id, String name, List<Window> windows, List<Occupation.Range> occupied) {

        public Professional {
            windows = List.copyOf(windows);
            occupied = List.copyOf(occupied);
        }
    }

    /**
     * One local date's inputs.
     *
     * @param opening the branch's opening hours, or null when it is closed
     * @param eligible those who perform the service and are on the team, in the order the Team page lists them
     * @param segments the booking's segments, in order
     * @param intervalMinutes the rules' slot interval
     * @param strategy how a "No preference" time picks among those free
     */
    public record Day(
            LocalDate date,
            ZoneId zone,
            Window opening,
            List<Professional> eligible,
            List<ServiceSegment> segments,
            int intervalMinutes,
            NoPreference strategy) {

        public Day {
            eligible = List.copyOf(eligible);
            segments = List.copyOf(segments);
            if (intervalMinutes <= 0) {
                throw new IllegalArgumentException("A slot interval is above zero");
            }
        }
    }

    /**
     * How near and how far ahead the audience may book.
     *
     * @param noticeMinutes the rules' minimum notice: applied to customers only
     * @param horizonDays the rules' horizon: applied to customers only
     */
    public record Audiences(Audience audience, Instant now, int noticeMinutes, int horizonDays) {

        /** Whether the audience may book on the date at all. */
        boolean allows(LocalDate date, ZoneId zone) {
            if (audience == Audience.STAFF) {
                return true;
            }
            LocalDate today = now.atZone(zone).toLocalDate();
            return !date.isBefore(today) && !date.isAfter(today.plusDays(horizonDays));
        }

        /** Whether a start may be offered: after now, and for a customer not before the notice. */
        boolean allows(Instant start) {
            if (!start.isAfter(now)) {
                return false;
            }
            return audience == Audience.STAFF || !start.isBefore(now.plus(Duration.ofMinutes(noticeMinutes)));
        }
    }

    /** The times on the date, in time order, each with who would take it; or why there are none. */
    public static Result answer(Day day, Audiences audiences) {
        if (!audiences.allows(day.date(), day.zone())) {
            return Result.none(Empty.OUTSIDE_BOOKING_WINDOW);
        }
        if (day.opening() == null) {
            return Result.none(Empty.CLOSED);
        }
        if (day.eligible().isEmpty()) {
            return Result.none(Empty.NOBODY_PERFORMS);
        }
        if (day.eligible().stream()
                .allMatch(professional -> professional.windows().isEmpty())) {
            return Result.none(Empty.NOT_WORKING);
        }
        Plan plan = new Plan(day);
        List<FreeTime> times = new ArrayList<>();
        LocalDateTime midnight = day.date().atStartOfDay();
        for (int minute = 0; minute < 24 * 60; minute += day.intervalMinutes()) {
            LocalDateTime local = midnight.plusMinutes(minute);
            Instant start = SalonTime.toInstant(local, day.zone());
            if (!SalonTime.toLocal(start, day.zone()).toLocalDateTime().equals(local) || !audiences.allows(start)) {
                continue;
            }
            Occupation booking = Occupation.of(start, day.segments());
            if (!plan.opening.holds(booking)) {
                continue;
            }
            plan.ordered.stream()
                    .filter(candidate -> candidate.free(booking))
                    .findFirst()
                    .ifPresent(
                            chosen -> times.add(new FreeTime(local.toLocalTime(), start, chosen.id(), chosen.name())));
        }
        return times.isEmpty() ? Result.none(Empty.FULLY_BOOKED) : new Result(times, null);
    }

    /**
     * Who could take a booking starting at {@code start}, in the strategy's order: what a "No preference" hold tries,
     * one after the other, until the database accepts one. The audience's limits are the caller's to check.
     */
    public static Assignment assign(Day day, Instant start) {
        if (day.opening() == null) {
            return new Assignment.Unavailable("The salon is closed on that date.");
        }
        Plan plan = new Plan(day);
        Occupation booking = Occupation.of(start, day.segments());
        if (!plan.opening.holds(booking)) {
            return new Assignment.Unavailable("The booking falls outside the salon's opening hours.");
        }
        if (day.eligible().isEmpty()) {
            return new Assignment.Unavailable("Nobody on the team performs this service.");
        }
        List<Candidate> working = plan.ordered.stream()
                .filter(candidate -> candidate.works(booking))
                .toList();
        if (working.isEmpty()) {
            return new Assignment.Unavailable("Nobody who performs this service works at that time.");
        }
        return new Assignment.Candidates(working.stream()
                .filter(candidate -> candidate.free(booking))
                .map(Candidate::professional)
                .toList());
    }

    /**
     * The times, or none and why.
     *
     * @param reason null exactly when there are times
     */
    public record Result(List<FreeTime> times, Empty reason) {

        public Result {
            times = List.copyOf(times);
        }

        static Result none(Empty reason) {
            return new Result(List.of(), reason);
        }
    }

    /** What a "No preference" hold may do at one start. */
    public sealed interface Assignment {

        /** Nobody could take it whatever is booked: the detail says why. */
        record Unavailable(String detail) implements Assignment {}

        /** Those free for it, in the order to try; empty when everyone who works then is taken. */
        record Candidates(List<Professional> inOrder) implements Assignment {

            public Candidates {
                inOrder = List.copyOf(inOrder);
            }
        }
    }

    /** The day's windows as instants, and the eligible in the order the strategy tries them. */
    private static final class Plan {

        private final Span opening;
        private final List<Candidate> ordered;

        Plan(Day day) {
            this.opening = Span.of(day.date(), day.opening(), day.zone());
            Instant dayStart = SalonTime.toInstant(day.date().atStartOfDay(), day.zone());
            Instant dayEnd = SalonTime.toInstant(day.date().plusDays(1).atStartOfDay(), day.zone());
            List<Candidate> candidates = new ArrayList<>();
            for (Professional professional : day.eligible()) {
                candidates.add(new Candidate(
                        professional,
                        professional.windows().stream()
                                .map(window -> Span.of(day.date(), window, day.zone()))
                                .toList(),
                        bookedSeconds(professional.occupied(), dayStart, dayEnd)));
            }
            Comparator<Candidate> byLoad = Comparator.comparingLong(Candidate::bookedSeconds);
            // A stable sort: equal loads keep the Team page's order.
            candidates.sort(day.strategy() == NoPreference.MOST_BOOKED ? byLoad.reversed() : byLoad);
            this.ordered = List.copyOf(candidates);
        }

        /** How long the ranges occupy the professional within the local date. */
        private static long bookedSeconds(List<Occupation.Range> occupied, Instant dayStart, Instant dayEnd) {
            long seconds = 0;
            for (Occupation.Range range : occupied) {
                Instant from = range.from().isAfter(dayStart) ? range.from() : dayStart;
                Instant to = range.to().isBefore(dayEnd) ? range.to() : dayEnd;
                if (from.isBefore(to)) {
                    seconds += Duration.between(from, to).getSeconds();
                }
            }
            return seconds;
        }
    }

    private record Span(Instant from, Instant to) {

        static Span of(LocalDate date, Window window, ZoneId zone) {
            return new Span(
                    SalonTime.toInstant(LocalDateTime.of(date, window.from()), zone),
                    SalonTime.toInstant(LocalDateTime.of(date, window.to()), zone));
        }

        boolean holds(Occupation booking) {
            return !booking.startsAt().isBefore(from) && !booking.endsAt().isAfter(to);
        }
    }

    private record Candidate(Professional professional, List<Span> windows, long bookedSeconds) {

        UUID id() {
            return professional.id();
        }

        String name() {
            return professional.name();
        }

        boolean works(Occupation booking) {
            return windows.stream().anyMatch(window -> window.holds(booking));
        }

        boolean free(Occupation booking) {
            return works(booking) && !booking.overlapsAny(professional.occupied());
        }
    }
}
