package com.salonplatform.platform.service.impl;

import com.salonplatform.identity.inprocess.IdentityProvisioning;
import com.salonplatform.platform.inprocess.SalonSeatUsage;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * A salon's staff seats used, the one count {@code usage.staffSeats} reports and a plan move checks (decisions 099,
 * 100): its active accounts plus what other modules add, read in the caller's platform transaction.
 */
@Component
class StaffSeatUsage {

    private final IdentityProvisioning identity;
    private final ObjectProvider<SalonSeatUsage> others;

    StaffSeatUsage(IdentityProvisioning identity, ObjectProvider<SalonSeatUsage> others) {
        this.identity = identity;
        this.others = others;
    }

    long used(UUID salonId) {
        return identity.activeStaffCount(salonId)
                + others.orderedStream()
                        .mapToLong(usage -> usage.seatsUsed(salonId))
                        .sum();
    }
}
