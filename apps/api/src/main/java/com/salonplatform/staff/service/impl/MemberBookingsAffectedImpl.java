package com.salonplatform.staff.service.impl;

import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.staff.AffectedBooking;
import com.salonplatform.staff.MemberBookingsAffected;
import com.salonplatform.staff.TimeWindow;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The answer until bookings exist (decision 102): no booking is affected. F07 deletes this class when {@code booking}
 * provides its own {@link MemberBookingsAffected}.
 */
@Service
class MemberBookingsAffectedImpl implements MemberBookingsAffected {

    @Override
    public List<AffectedBooking> outsideWeek(
            UUID memberId, Map<DayOfWeek, List<TimeWindow>> windows, LocalDate from, Set<LocalDate> exceptDates) {
        TenantContext.require();
        return List.of();
    }

    @Override
    public List<AffectedBooking> outsideDate(UUID memberId, LocalDate date, List<TimeWindow> windows) {
        TenantContext.require();
        return List.of();
    }
}
