package com.salonplatform.staff.service.impl;

import com.salonplatform.staff.AffectedBooking;
import com.salonplatform.staff.BookingsOnDate;
import com.salonplatform.staff.WeekdayHours;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The answer until bookings exist (F04-S03): no booking is affected. F07's {@code booking} implementation is
 * the {@code @Primary} bean of the type, which replaces this one with no change here (decision 087).
 */
@Service
class NoBookingsOnDate implements BookingsOnDate {

    @Override
    public List<AffectedBooking> on(UUID branchId, LocalDate date) {
        return List.of();
    }

    @Override
    public List<AffectedBooking> outsideHours(UUID branchId, List<WeekdayHours> week, LocalDate from) {
        return List.of();
    }
}
