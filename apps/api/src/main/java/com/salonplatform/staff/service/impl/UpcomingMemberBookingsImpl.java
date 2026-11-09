package com.salonplatform.staff.service.impl;

import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.staff.UpcomingMemberBookings;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The answer until bookings exist (decision 100): no member has upcoming bookings, so nothing is refused. F07 deletes
 * this class when {@code booking} provides its own {@link UpcomingMemberBookings}.
 */
@Service
class UpcomingMemberBookingsImpl implements UpcomingMemberBookings {

    @Override
    public long countFor(UUID memberId) {
        TenantContext.require();
        return 0;
    }

    @Override
    public Set<UUID> servicesBookedFor(UUID memberId, Set<UUID> serviceIds) {
        TenantContext.require();
        return Set.of();
    }
}
