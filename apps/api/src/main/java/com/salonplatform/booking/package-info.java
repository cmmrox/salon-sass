/**
 * The {@code booking} module. From F04-S04: the salon's booking rules (when customers can book, the deposit,
 * and what a late cancellation, a no-show and rescheduling allow), each save an immutable version, the newest
 * current (decision 089). The current rules are public at {@code GET /api/v1/salon/booking-rules}; settings
 * staff keep them and read their history. It provides {@link com.salonplatform.booking.BookingPolicies}, the
 * one contract F07 (availability), F08 (deposits) and F09 (cancellation, no-show, rescheduling) read the rules
 * through. Branches are {@code branding}'s, checked through {@link com.salonplatform.branding.SalonBranches};
 * the salon's currency is {@code platform}'s {@link com.salonplatform.platform.SalonCurrency}; who acts is
 * {@code identity}'s {@link com.salonplatform.identity.Principals}, and how an author is named its
 * {@link com.salonplatform.identity.StaffLabels}. Nothing depends on {@code booking}.
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
package com.salonplatform.booking;

import org.springframework.modulith.ApplicationModule;
