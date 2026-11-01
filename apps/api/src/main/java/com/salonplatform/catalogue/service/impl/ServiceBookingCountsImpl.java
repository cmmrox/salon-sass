package com.salonplatform.catalogue.service.impl;

import com.salonplatform.catalogue.ServiceBookingCounts;
import com.salonplatform.shared.tenancy.TenantContext;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The answer until bookings exist (decision 095): no service has been booked, so "Most booked" shows the services
 * the salon chose. F07 deletes this class when {@code booking} provides its own {@link ServiceBookingCounts}.
 */
@Service
class ServiceBookingCountsImpl implements ServiceBookingCounts {

    @Override
    public Map<UUID, Long> countsSince(Instant from) {
        TenantContext.require();
        return Map.of();
    }
}
