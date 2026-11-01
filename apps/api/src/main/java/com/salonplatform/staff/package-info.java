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
 * <p>From F06-S01: the salon's team members, their photos and the services each performs, under
 * {@code /api/v1/team}, with {@code team.read} and {@code team.manage} (decision 100); the profiles shown for
 * online booking are public there. It provides {@link com.salonplatform.staff.TeamMembers}, which F07 and F10 use,
 * and declares {@link com.salonplatform.staff.UpcomingMemberBookings}, which {@code booking} implements in F07.
 * Categories and services are {@code catalogue}'s, read through
 * {@link com.salonplatform.catalogue.CatalogueListings}. A member may link to one of {@code identity}'s accounts;
 * a staff seat is held across both modules through {@link com.salonplatform.identity.inprocess.StaffSeats}, and
 * {@code staff} answers {@link com.salonplatform.identity.inprocess.TeamMemberSeats} and platform's
 * {@link com.salonplatform.platform.inprocess.SalonSeatUsage}.
 *
 * <p>What it owns, the layout inside it and the rules between modules are in
 * {@code docs/engineering/backend.md}.
 */
@ApplicationModule(
        allowedDependencies = {
            "branding",
            "catalogue",
            "identity",
            "identity :: inprocess",
            "platform",
            "platform :: inprocess",
            "shared :: audit",
            "shared :: images",
            "shared :: lifecycle",
            "shared :: persistence",
            "shared :: tenancy",
            "shared :: web"
        })
package com.salonplatform.staff;

import org.springframework.modulith.ApplicationModule;
