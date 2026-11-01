package com.salonplatform.catalogue.service.impl;

import com.salonplatform.catalogue.UpcomingServiceBookings;
import com.salonplatform.shared.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The answer until bookings exist (decision 093): no service has upcoming bookings, so a delete is never blocked.
 * F07 deletes this class when {@code booking} provides its own {@link UpcomingServiceBookings}.
 */
@Service
class UpcomingServiceBookingsImpl implements UpcomingServiceBookings {

    @Override
    public boolean anyFor(UUID serviceId) {
        TenantContext.require();
        return false;
    }
}
