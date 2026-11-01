package com.salonplatform.staff.service.impl;

import com.salonplatform.branding.SalonBranches;
import com.salonplatform.identity.Principal;
import com.salonplatform.identity.Principals;
import com.salonplatform.platform.HolidayCalendar;
import com.salonplatform.platform.PublishedHoliday;
import com.salonplatform.platform.PublishedHolidayYear;
import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.TenantContext;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What every staff use case settles before it opens its transaction (docs/architecture/multi-tenancy.md): the
 * salon, who acts and with which permission, the salon's default branch, and the salon's today from the
 * application's clock in the salon's time zone. The published holidays are read here too, outside any
 * transaction, as the calendar's contract asks.
 */
@Component
class StaffScope {

    static final String READ = "settings.read";
    static final String MANAGE = "settings.manage";

    /** The salon a use case acts for, its default branch, its today, and who acts (null when public). */
    record Scope(CurrentTenant salon, Principal actor, UUID branch, LocalDate today) {}

    private final Principals principals;
    private final SalonBranches branches;
    private final HolidayCalendar calendar;
    private final Clock clock;

    StaffScope(Principals principals, SalonBranches branches, HolidayCalendar calendar, Clock clock) {
        this.principals = principals;
        this.branches = branches;
        this.calendar = calendar;
        this.clock = clock;
    }

    /** The scope for a caller who holds the permission. */
    Scope of(String permission) {
        CurrentTenant salon = TenantContext.require();
        Principal actor = principals.require(permission);
        return new Scope(salon, actor, branches.defaultBranch().id(), today(salon));
    }

    /** The scope of a public request: the hostname's salon, no one acting. */
    Scope publicly() {
        CurrentTenant salon = TenantContext.require();
        return new Scope(salon, null, branches.defaultBranch().id(), today(salon));
    }

    /** The salon's local date now. */
    LocalDate today(CurrentTenant salon) {
        return LocalDate.ofInstant(clock.instant(), salon.timeZone());
    }

    /** The published years covering the dates, oldest first. */
    List<PublishedHolidayYear> years(LocalDate from, LocalDate to) {
        return calendar.published(from.getYear(), to.getYear());
    }

    /** The published years from the salon's current year on. */
    List<PublishedHolidayYear> yearsFrom(int year) {
        return calendar.published(year, HolidayCalendar.LAST_YEAR);
    }

    /** The published days covering the dates, by date. */
    Map<LocalDate, PublishedHoliday> holidays(LocalDate from, LocalDate to) {
        Map<LocalDate, PublishedHoliday> byDate = new LinkedHashMap<>();
        for (PublishedHolidayYear year : years(from, to)) {
            for (PublishedHoliday day : year.days()) {
                byDate.put(day.date(), day);
            }
        }
        return byDate;
    }
}
