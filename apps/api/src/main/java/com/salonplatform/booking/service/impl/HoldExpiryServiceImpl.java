package com.salonplatform.booking.service.impl;

import com.salonplatform.booking.domain.BookingAction;
import com.salonplatform.booking.entity.Booking;
import com.salonplatform.booking.repository.BookingRepository;
import com.salonplatform.booking.service.HoldExpiryService;
import com.salonplatform.booking.service.impl.BookingChanges.Why;
import com.salonplatform.shared.tenancy.TenantContext;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Ends run-out holds a batch at a time, skipping any a placement has locked to end itself. Joins the scheduler's
 * transaction for the salon.
 */
@Service
class HoldExpiryServiceImpl implements HoldExpiryService {

    /** The most holds one run ends for a salon; the next run, a minute later, takes the rest. */
    static final int BATCH = 500;

    private final BookingRepository bookings;
    private final BookingChanges changes;

    HoldExpiryServiceImpl(BookingRepository bookings, BookingChanges changes) {
        this.bookings = bookings;
        this.changes = changes;
    }

    @Override
    public int endRunOutHolds(Instant now) {
        ZoneId zone = TenantContext.require().timeZone();
        List<Booking> holds = bookings.lockRunOutHolds(now, PageRequest.of(0, BATCH));
        for (Booking hold : holds) {
            changes.take(hold, BookingAction.EXPIRE, zone, null, Why.NONE, now);
        }
        bookings.flush();
        return holds.size();
    }
}
