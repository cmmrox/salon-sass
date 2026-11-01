package com.salonplatform.staff.service.impl;

import com.salonplatform.branding.SalonBranches;
import com.salonplatform.platform.PublishedHoliday;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.NotFoundException;
import com.salonplatform.staff.OpeningDay;
import com.salonplatform.staff.OpeningSchedule;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resolves the salon first, checks the branch through {@link SalonBranches} and reads the published calendar,
 * each outside any transaction, then reads the branch's hours, choices and closures in one read-only tenant
 * transaction of its own.
 */
@Service
class OpeningScheduleImpl implements OpeningSchedule {

    private final SalonBranches branches;
    private final StaffScope scope;
    private final OpeningCalendar calendar;
    private final TransactionTemplate reading;

    OpeningScheduleImpl(
            SalonBranches branches,
            StaffScope scope,
            OpeningCalendar calendar,
            PlatformTransactionManager transactions) {
        this.branches = branches;
        this.scope = scope;
        this.calendar = calendar;
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
    }

    @Override
    public OpeningDay on(UUID branchId, LocalDate date) {
        return between(branchId, date, date).getFirst();
    }

    @Override
    public List<OpeningDay> between(UUID branchId, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new IllegalArgumentException("a range of 1 to " + MAX_DAYS + " days, the last not before the first");
        }
        TenantContext.require();
        if (!branches.belongsToCurrentSalon(branchId)) {
            throw new NotFoundException();
        }
        Map<LocalDate, PublishedHoliday> holidays = scope.holidays(from, to);
        return reading.execute(status -> calendar.between(branchId, from, to, holidays));
    }
}
