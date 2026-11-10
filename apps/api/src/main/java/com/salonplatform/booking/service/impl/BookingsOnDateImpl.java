package com.salonplatform.booking.service.impl;

import com.salonplatform.booking.entity.Booking;
import com.salonplatform.booking.repository.BookingRepository;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.staff.AffectedBooking;
import com.salonplatform.staff.BookingsOnDate;
import com.salonplatform.staff.WeekdayHours;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** {@code staff}'s question for closures and hours changes (STF-04, AC-13), from the branch's expected bookings. */
@Service
class BookingsOnDateImpl implements BookingsOnDate {

    private final BookingRepository bookings;
    private final BookingReads reads;
    private final Clock clock;

    BookingsOnDateImpl(BookingRepository bookings, BookingReads reads, Clock clock) {
        this.bookings = bookings;
        this.reads = reads;
        this.clock = clock;
    }

    @Override
    public List<AffectedBooking> on(UUID branchId, LocalDate date) {
        ZoneId zone = TenantContext.require().timeZone();
        return reads.read(() -> bookings
                .expectedInBranch(
                        branchId,
                        BookingReads.startOf(date, zone),
                        BookingReads.startOf(date.plusDays(1), zone),
                        clock.instant())
                .stream()
                .map(booking -> BookingReads.affected(booking, zone))
                .toList());
    }

    @Override
    public List<AffectedBooking> outsideHours(UUID branchId, List<WeekdayHours> week, LocalDate from) {
        ZoneId zone = TenantContext.require().timeZone();
        Map<java.time.DayOfWeek, WeekdayHours> byDay =
                week.stream().collect(Collectors.toMap(WeekdayHours::day, Function.identity()));
        return reads.read(() ->
                bookings
                        .expectedInBranch(branchId, BookingReads.startOf(from, zone), BookingReads.FAR, clock.instant())
                        .stream()
                        .filter(booking -> outside(
                                booking,
                                byDay.get(BookingReads.dateOf(booking, zone).getDayOfWeek()),
                                zone))
                        .map(booking -> BookingReads.affected(booking, zone))
                        .toList());
    }

    private static boolean outside(Booking booking, WeekdayHours hours, ZoneId zone) {
        return hours == null
                || hours.opens() == null
                || !BookingReads.within(booking, hours.opens(), hours.closes(), zone);
    }
}
