package com.salonplatform.booking.service.impl;

import com.salonplatform.booking.domain.BookingState;
import com.salonplatform.booking.repository.BookingRepository;
import com.salonplatform.catalogue.ServiceBookingCounts;
import com.salonplatform.shared.tenancy.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** The catalogue's "Most booked" (CAT-06, AC-13): bookings that have started, by service, up to now by the clock. */
@Service
class ServiceBookingCountsImpl implements ServiceBookingCounts {

    private final BookingRepository bookings;
    private final BookingReads reads;
    private final Clock clock;

    ServiceBookingCountsImpl(BookingRepository bookings, BookingReads reads, Clock clock) {
        this.bookings = bookings;
        this.reads = reads;
        this.clock = clock;
    }

    @Override
    public Map<UUID, Long> countsSince(Instant from) {
        TenantContext.require();
        Instant now = clock.instant();
        return reads.read(() -> {
            Map<UUID, Long> counts = new HashMap<>();
            for (Object[] row : bookings.countByService(
                    from, now, BookingState.BOOKED.stream().map(BookingState::value).toList())) {
                counts.put((UUID) row[0], (Long) row[1]);
            }
            return Map.copyOf(counts);
        });
    }
}
