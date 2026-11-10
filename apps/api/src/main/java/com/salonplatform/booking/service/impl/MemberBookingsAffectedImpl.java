package com.salonplatform.booking.service.impl;

import com.salonplatform.booking.entity.Booking;
import com.salonplatform.booking.repository.BookingRepository;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.staff.AffectedBooking;
import com.salonplatform.staff.MemberBookingsAffected;
import com.salonplatform.staff.MemberPeriod;
import com.salonplatform.staff.TimeWindow;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * {@code staff}'s questions about a person's schedule, leave and blocked time (STF-02, STF-03, STF-05, AC-13), from the
 * person's expected bookings. A booking is outside when it does not lie wholly inside one window of its date.
 */
@Service
class MemberBookingsAffectedImpl implements MemberBookingsAffected {

    private final BookingRepository bookings;
    private final BookingReads reads;
    private final Clock clock;

    MemberBookingsAffectedImpl(BookingRepository bookings, BookingReads reads, Clock clock) {
        this.bookings = bookings;
        this.reads = reads;
        this.clock = clock;
    }

    @Override
    public List<AffectedBooking> outsideWeek(
            UUID memberId, Map<DayOfWeek, List<TimeWindow>> windows, LocalDate from, Set<LocalDate> exceptDates) {
        ZoneId zone = TenantContext.require().timeZone();
        return reads.read(() ->
                bookings
                        .expectedOfMember(memberId, BookingReads.startOf(from, zone), BookingReads.FAR, clock.instant())
                        .stream()
                        .filter(booking -> !exceptDates.contains(BookingReads.dateOf(booking, zone)))
                        .filter(booking -> outside(
                                booking,
                                windows.getOrDefault(
                                        BookingReads.dateOf(booking, zone).getDayOfWeek(), List.of()),
                                zone))
                        .map(booking -> BookingReads.affected(booking, zone))
                        .toList());
    }

    @Override
    public List<AffectedBooking> outsideDate(UUID memberId, LocalDate date, List<TimeWindow> windows) {
        ZoneId zone = TenantContext.require().timeZone();
        return reads.read(() -> onDates(memberId, date, date, zone).stream()
                .filter(booking -> outside(booking, windows, zone))
                .map(booking -> BookingReads.affected(booking, zone))
                .toList());
    }

    @Override
    public Map<MemberPeriod, List<AffectedBooking>> inPeriods(UUID memberId, Set<MemberPeriod> periods) {
        ZoneId zone = TenantContext.require().timeZone();
        if (periods.isEmpty()) {
            return Map.of();
        }
        LocalDate first = periods.stream()
                .map(MemberPeriod::firstDay)
                .min(Comparator.naturalOrder())
                .orElseThrow();
        LocalDate last = periods.stream()
                .map(MemberPeriod::lastDay)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        List<Booking> booked = reads.read(() -> onDates(memberId, first, last, zone));
        Map<MemberPeriod, List<AffectedBooking>> answer = new LinkedHashMap<>();
        for (MemberPeriod period : periods) {
            List<AffectedBooking> inIt = booked.stream()
                    .filter(booking -> in(booking, period, zone))
                    .map(booking -> BookingReads.affected(booking, zone))
                    .toList();
            if (!inIt.isEmpty()) {
                answer.put(period, inIt);
            }
        }
        return answer;
    }

    private List<Booking> onDates(UUID memberId, LocalDate first, LocalDate last, ZoneId zone) {
        return bookings.expectedOfMember(
                memberId,
                BookingReads.startOf(first, zone),
                BookingReads.startOf(last.plusDays(1), zone),
                clock.instant());
    }

    private static boolean outside(Booking booking, List<TimeWindow> windows, ZoneId zone) {
        return windows.stream()
                .noneMatch(window -> BookingReads.within(booking, window.starts(), window.finishes(), zone));
    }

    private static boolean in(Booking booking, MemberPeriod period, ZoneId zone) {
        LocalDate date = BookingReads.dateOf(booking, zone);
        if (date.isBefore(period.firstDay()) || date.isAfter(period.lastDay())) {
            return false;
        }
        return period.time() == null
                || BookingReads.overlaps(
                        booking, date, period.time().starts(), period.time().finishes(), zone);
    }
}
