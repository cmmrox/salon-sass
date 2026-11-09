package com.salonplatform.booking.service.impl;

import com.salonplatform.booking.Availability;
import com.salonplatform.booking.BookingRules;
import com.salonplatform.booking.domain.FreeTimes;
import com.salonplatform.branding.SalonBranches;
import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.staff.OpeningDay;
import com.salonplatform.staff.OpeningSchedule;
import com.salonplatform.staff.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The availability calculation as other features call it (F07-S02): {@link AvailabilityInputs} gathers one date's
 * inputs, {@link FreeTimes} works out the answer, with the audience's limits from the current booking rules.
 */
@Service
class AvailabilityImpl implements Availability {

    private final AvailabilityInputs inputs;
    private final OpeningSchedule opening;
    private final SalonBranches branches;
    private final Clock clock;

    AvailabilityImpl(AvailabilityInputs inputs, OpeningSchedule opening, SalonBranches branches, Clock clock) {
        this.inputs = inputs;
        this.opening = opening;
        this.branches = branches;
        this.clock = clock;
    }

    @Override
    public Answer freeTimes(Query query) {
        CurrentTenant salon = TenantContext.require();
        BookingRules rules = inputs.rules();
        AvailabilityInputs.BookedService service = inputs.service(query.serviceId(), query.variantId());
        List<TeamMember> eligible = inputs.eligible(query.serviceId(), query.memberId());
        Instant now = clock.instant();
        FreeTimes.Day day = inputs.day(
                branches.defaultBranch().id(),
                salon.timeZone(),
                query.date(),
                rules,
                service.segments(),
                eligible,
                now);
        FreeTimes.Result result = FreeTimes.answer(
                day,
                new FreeTimes.Audiences(
                        query.audience(),
                        now,
                        rules.terms().minimumNoticeMinutes(),
                        rules.terms().bookingHorizonDays()));
        return new Answer(query.date(), result.times(), result.reason());
    }

    @Override
    public List<LocalDate> openDays(LocalDate from, LocalDate to) {
        TenantContext.require();
        return opening.between(branches.defaultBranch().id(), from, to).stream()
                .filter(OpeningDay::open)
                .map(OpeningDay::date)
                .toList();
    }
}
