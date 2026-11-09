package com.salonplatform.booking.service.impl;

import com.salonplatform.booking.domain.BookingState;
import com.salonplatform.booking.repository.BookingRepository;
import com.salonplatform.catalogue.ServiceBookingCounts;
import com.salonplatform.shared.tenancy.TenantContext;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** The catalogue's "Most booked" (CAT-06, AC-13): bookings made, by service. */
@Service
class ServiceBookingCountsImpl implements ServiceBookingCounts {

    private final BookingRepository bookings;
    private final BookingReads reads;

    ServiceBookingCountsImpl(BookingRepository bookings, BookingReads reads) {
        this.bookings = bookings;
        this.reads = reads;
    }

    @Override
    public Map<UUID, Long> countsSince(Instant from) {
        TenantContext.require();
        return reads.read(() -> {
            Map<UUID, Long> counts = new HashMap<>();
            for (Object[] row : bookings.countByService(
                    from, BookingState.BOOKED.stream().map(BookingState::value).toList())) {
                counts.put((UUID) row[0], (Long) row[1]);
            }
            return Map.copyOf(counts);
        });
    }
}
