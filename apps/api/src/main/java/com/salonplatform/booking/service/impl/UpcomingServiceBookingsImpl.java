package com.salonplatform.booking.service.impl;

import com.salonplatform.booking.repository.BookingRepository;
import com.salonplatform.catalogue.UpcomingServiceBookings;
import com.salonplatform.shared.tenancy.TenantContext;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** The catalogue's guard before a service is deleted (CAT-01, AC-13). */
@Service
class UpcomingServiceBookingsImpl implements UpcomingServiceBookings {

    private final BookingRepository bookings;
    private final BookingReads reads;
    private final Clock clock;

    UpcomingServiceBookingsImpl(BookingRepository bookings, BookingReads reads, Clock clock) {
        this.bookings = bookings;
        this.reads = reads;
        this.clock = clock;
    }

    @Override
    public boolean anyFor(UUID serviceId) {
        TenantContext.require();
        return Boolean.TRUE.equals(reads.read(() -> bookings.anyUpcomingForService(serviceId, clock.instant())));
    }
}
