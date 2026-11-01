package com.salonplatform.staff.service.impl;

import com.salonplatform.platform.inprocess.SalonSeatUsage;
import com.salonplatform.staff.repository.StaffPlatformFunctions;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** A team member with no linked account takes a staff seat (decision 099), read on the platform connection. */
@Service
class SalonSeatUsageImpl implements SalonSeatUsage {

    private final StaffPlatformFunctions functions;

    SalonSeatUsageImpl(StaffPlatformFunctions functions) {
        this.functions = functions;
    }

    @Override
    public long seatsUsed(UUID tenantId) {
        return functions.activeUnlinkedMemberCount(tenantId);
    }
}
