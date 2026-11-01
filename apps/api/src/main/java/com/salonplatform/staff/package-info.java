/**
 * The {@code staff} module. From F04-S03: each branch's weekly opening hours, its choices for the operator's
 * published holidays and its special closures, served to the salon's settings staff and, without the
 * closures' reasons, to anyone at {@code GET /api/v1/salon/opening-hours}. It provides
 * {@link com.salonplatform.staff.OpeningSchedule}, the answer F07's availability uses, and declares
 * {@link com.salonplatform.staff.BookingsOnDate}, which {@code booking} implements in F07 (decision 087).
 * Branches are {@code branding}'s, checked through {@link com.salonplatform.branding.SalonBranches}; the
 * published calendar is {@code platform}'s, read through {@link com.salonplatform.platform.HolidayCalendar};
 * who acts is {@code identity}'s {@link com.salonplatform.identity.Principals}. No module depends on
 * {@code staff} except {@code booking}, later.
 *
 * <p>What it owns, the layout inside it and the rules between modules are in
 * {@code docs/engineering/backend.md}.
 */
@ApplicationModule(
        allowedDependencies = {
            "branding",
            "identity",
            "platform",
            "shared :: audit",
            "shared :: lifecycle",
            "shared :: persistence",
            "shared :: tenancy",
            "shared :: web"
        })
package com.salonplatform.staff;

import org.springframework.modulith.ApplicationModule;
