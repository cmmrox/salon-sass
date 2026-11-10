package com.salonplatform.booking.service.impl;

import com.salonplatform.booking.entity.Booking;
import com.salonplatform.shared.time.SalonTime;
import com.salonplatform.staff.AffectedBooking;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the contracts booking implements for {@code staff} and {@code catalogue} share: a read-only tenant transaction
 * of their own that never joins the caller's, and the salon's local dates and times of a booking.
 */
@Component
class BookingReads {

    /** Later than any booking: the end of an open-ended range. */
    static final Instant FAR = Instant.parse("9999-12-31T00:00:00Z");

    private final TransactionTemplate reading;

    BookingReads(PlatformTransactionManager transactions) {
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
        this.reading.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    <T> T read(Supplier<T> work) {
        return reading.execute(status -> work.get());
    }

    /** The salon's midnight at the start of the date. */
    static Instant startOf(LocalDate date, ZoneId zone) {
        return SalonTime.toInstant(date.atStartOfDay(), zone);
    }

    /** The booking as the settings screens list it. */
    static AffectedBooking affected(Booking booking, ZoneId zone) {
        ZonedDateTime starts = booking.getStartsAt().atZone(zone);
        return new AffectedBooking(
                starts.toLocalDate(), starts.toLocalTime(), booking.getCustomerName(), booking.getServiceName());
    }

    static LocalDate dateOf(Booking booking, ZoneId zone) {
        return booking.getStartsAt().atZone(zone).toLocalDate();
    }

    /** Whether the booking lies wholly between two local times of its own date. */
    static boolean within(Booking booking, LocalTime from, LocalTime to, ZoneId zone) {
        LocalDate date = dateOf(booking, zone);
        Instant opens = SalonTime.toInstant(LocalDateTime.of(date, from), zone);
        Instant closes = SalonTime.toInstant(LocalDateTime.of(date, to), zone);
        return !booking.getStartsAt().isBefore(opens) && !booking.getEndsAt().isAfter(closes);
    }

    /** Whether any of the booking overlaps the local times of a date; a booking that only touches them does not. */
    static boolean overlaps(Booking booking, LocalDate date, LocalTime from, LocalTime to, ZoneId zone) {
        Instant starts = SalonTime.toInstant(LocalDateTime.of(date, from), zone);
        Instant finishes = SalonTime.toInstant(LocalDateTime.of(date, to), zone);
        return booking.getStartsAt().isBefore(finishes) && booking.getEndsAt().isAfter(starts);
    }
}
