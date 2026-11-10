package com.salonplatform.booking.service.impl;

import com.salonplatform.booking.repository.BookingRepository;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.staff.inprocess.UpcomingMemberBookings;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** {@code staff}'s guard before a person is removed or loses services (STF-01, AC-13). */
@Service
class UpcomingMemberBookingsImpl implements UpcomingMemberBookings {

    private final BookingRepository bookings;
    private final BookingReads reads;
    private final Clock clock;

    UpcomingMemberBookingsImpl(BookingRepository bookings, BookingReads reads, Clock clock) {
        this.bookings = bookings;
        this.reads = reads;
        this.clock = clock;
    }

    @Override
    public long countFor(UUID memberId) {
        TenantContext.require();
        return reads.joining(() -> bookings.countUpcomingForMember(memberId, clock.instant()));
    }

    @Override
    public Set<UUID> servicesBookedFor(UUID memberId, Set<UUID> serviceIds) {
        TenantContext.require();
        if (serviceIds.isEmpty()) {
            return Set.of();
        }
        return reads.joining(() -> Set.copyOf(bookings.servicesBookedFor(memberId, serviceIds, clock.instant())));
    }
}
