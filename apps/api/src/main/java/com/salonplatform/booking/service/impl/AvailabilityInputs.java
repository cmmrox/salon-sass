package com.salonplatform.booking.service.impl;

import com.salonplatform.booking.BookingPolicies;
import com.salonplatform.booking.BookingRules;
import com.salonplatform.booking.domain.FreeTimes;
import com.salonplatform.booking.domain.Occupation;
import com.salonplatform.booking.entity.Booking;
import com.salonplatform.booking.repository.BookingRepository;
import com.salonplatform.catalogue.ServiceSegment;
import com.salonplatform.catalogue.ServiceTerms;
import com.salonplatform.catalogue.ServiceTermsVersion;
import com.salonplatform.catalogue.ServiceVariant;
import com.salonplatform.shared.time.SalonTime;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.NotFoundException;
import com.salonplatform.staff.MemberWorkingDay;
import com.salonplatform.staff.OpeningDay;
import com.salonplatform.staff.OpeningSchedule;
import com.salonplatform.staff.TeamMember;
import com.salonplatform.staff.TeamMembers;
import com.salonplatform.staff.TeamSchedule;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Gathers what the availability calculation reads (F07-S02), for the reads and for a "No preference" hold alike: the
 * current booking rules, the service's terms (or its variant's), who is eligible, and one date's opening hours, working
 * windows and occupied time. Other modules' answers are read before the one read-only transaction that reads the day's
 * bookings, as their contracts ask: one {@link OpeningSchedule} call, one {@link TeamSchedule} call per eligible
 * professional, one read of the occupied time of them all.
 */
@Component
class AvailabilityInputs {

    private final BookingPolicies policies;
    private final ServiceTerms terms;
    private final HoldRequests requests;
    private final TeamMembers team;
    private final OpeningSchedule opening;
    private final TeamSchedule schedule;
    private final BookingRepository bookings;
    private final TransactionTemplate reading;

    AvailabilityInputs(
            BookingPolicies policies,
            ServiceTerms terms,
            HoldRequests requests,
            TeamMembers team,
            OpeningSchedule opening,
            TeamSchedule schedule,
            BookingRepository bookings,
            PlatformTransactionManager transactions) {
        this.policies = policies;
        this.terms = terms;
        this.requests = requests;
        this.team = team;
        this.opening = opening;
        this.schedule = schedule;
        this.bookings = bookings;
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
    }

    /** The salon's current rules; 409 {@code rules_not_set} before it first saves them. */
    BookingRules rules() {
        return policies.current().orElseThrow(() -> new ApplicationProblemException(409, ErrorCode.RULES_NOT_SET));
    }

    /**
     * The service's terms and the segments a booking of it (and of its variant) takes. 404 for a service that is not
     * the salon's; 400 or 404 on the variant as a hold refuses it.
     */
    BookedService service(UUID serviceId, UUID variantId) {
        ServiceTermsVersion service = terms.current(serviceId).orElseThrow(NotFoundException::new);
        Optional<ServiceVariant> variant = requests.variantOf(service, variantId);
        return new BookedService(
                service, variant, variant.map(ServiceVariant::segments).orElse(service.segments()));
    }

    /** A service as it is booked: its terms, its variant when it has one, and the segments that follow from them. */
    record BookedService(ServiceTermsVersion terms, Optional<ServiceVariant> variant, List<ServiceSegment> segments) {}

    /**
     * Who may take the booking: the named professional alone (404 when not on the salon's team, 409
     * {@code slot_unavailable} when they do not perform the service), or for "No preference" (null) everyone on the team
     * who performs it, shown online or not, in the order the Team page lists them.
     */
    List<TeamMember> eligible(UUID serviceId, UUID memberId) {
        if (memberId == null) {
            return team.performing(serviceId);
        }
        TeamMember member =
                team.member(memberId).filter(found -> !found.removed()).orElseThrow(NotFoundException::new);
        if (!team.servicesOf(member.id()).contains(serviceId)) {
            throw HoldTimes.unavailable("The professional does not perform this service.");
        }
        return List.of(member);
    }

    /** One date as the calculation reads it, by the rules' interval and strategy. */
    FreeTimes.Day day(
            UUID branch,
            ZoneId zone,
            LocalDate date,
            BookingRules rules,
            List<ServiceSegment> segments,
            List<TeamMember> eligible,
            Instant now) {
        OpeningDay open = opening.on(branch, date);
        FreeTimes.Window hours = open.open() ? new FreeTimes.Window(open.opens(), open.closes()) : null;
        List<FreeTimes.Professional> professionals = new ArrayList<>();
        if (hours != null && !eligible.isEmpty()) {
            Map<UUID, List<FreeTimes.Window>> windows = new HashMap<>();
            for (TeamMember member : eligible) {
                MemberWorkingDay working = schedule.on(member.id(), date);
                windows.put(
                        member.id(),
                        working.windows().stream()
                                .map(window -> new FreeTimes.Window(window.starts(), window.finishes()))
                                .toList());
            }
            Map<UUID, List<Occupation.Range>> occupied = occupied(eligible, date, zone, now);
            for (TeamMember member : eligible) {
                professionals.add(new FreeTimes.Professional(
                        member.id(),
                        member.name(),
                        windows.get(member.id()),
                        occupied.getOrDefault(member.id(), List.of())));
            }
        } else {
            eligible.forEach(member ->
                    professionals.add(new FreeTimes.Professional(member.id(), member.name(), List.of(), List.of())));
        }
        return new FreeTimes.Day(
                date,
                zone,
                hours,
                professionals,
                segments,
                rules.terms().slotIntervalMinutes(),
                rules.terms().noPreference());
    }

    /** The ranges each professional's live bookings and unexpired holds occupy over the local date, in one read. */
    private Map<UUID, List<Occupation.Range>> occupied(
            List<TeamMember> eligible, LocalDate date, ZoneId zone, Instant now) {
        Instant from = SalonTime.toInstant(date.atStartOfDay(), zone);
        Instant until = SalonTime.toInstant(date.plusDays(1).atStartOfDay(), zone);
        List<UUID> ids = eligible.stream().map(TeamMember::id).toList();
        return reading.execute(status -> {
            Map<UUID, List<Occupation.Range>> ranges = new HashMap<>();
            for (Booking booking : bookings.occupying(ids, from, until, now)) {
                ranges.computeIfAbsent(booking.getMemberId(), id -> new ArrayList<>())
                        .addAll(Occupation.of(booking.getStartsAt(), booking.segments())
                                .ranges());
            }
            return ranges;
        });
    }
}
