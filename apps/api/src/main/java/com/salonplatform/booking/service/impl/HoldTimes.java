package com.salonplatform.booking.service.impl;

import com.salonplatform.booking.domain.Occupation;
import com.salonplatform.booking.service.impl.BookingCallers.Caller;
import com.salonplatform.shared.time.SalonTime;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.staff.MemberWorkingDay;
import com.salonplatform.staff.OpeningDay;
import com.salonplatform.staff.OpeningSchedule;
import com.salonplatform.staff.TeamSchedule;
import com.salonplatform.staff.TimeWindow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * Whether a staff booking may take a time (AC-2, decision 108(c)): a start on a five-minute step, from now to one year
 * ahead by the salon's date, and the whole booking, start to the end of its last segment, inside the branch's opening
 * hours ({@link OpeningSchedule}) and inside one of the professional's working windows ({@link TeamSchedule}, which
 * already takes out breaks, leave and blocked time) on that date. The customer notice and horizon of BKG-02 do not
 * bound staff. Each refusal is 409 {@code slot_unavailable}, its detail saying which rule. Both schedules are read here,
 * before any transaction opens, as their contracts ask.
 */
@Component
class HoldTimes {

    static final int STEP_MINUTES = 5;

    private final OpeningSchedule opening;
    private final TeamSchedule schedule;

    HoldTimes(OpeningSchedule opening, TeamSchedule schedule) {
        this.opening = opening;
        this.schedule = schedule;
    }

    /** The start instant of a local date and time, when staff may book it now. */
    Instant startOf(Caller at, LocalDate date, LocalTime time, Instant now) {
        ZoneId zone = at.salon().timeZone();
        if (time.getMinute() % STEP_MINUTES != 0) {
            throw unavailable("A booking starts on a five-minute step.");
        }
        Instant startsAt = SalonTime.toInstant(LocalDateTime.of(date, time), zone);
        if (startsAt.isBefore(now)) {
            throw unavailable("The start time has passed.");
        }
        if (date.isAfter(now.atZone(zone).toLocalDate().plusYears(1))) {
            throw unavailable("A booking can be made up to one year ahead.");
        }
        return startsAt;
    }

    /** Refuses a booking that is not wholly inside the opening hours and one working window of its date. */
    void requireOpenAndWorking(Caller at, java.util.UUID memberId, LocalDate date, Occupation booking) {
        ZoneId zone = at.salon().timeZone();
        OpeningDay day = opening.on(at.branch(), date);
        if (!day.open()) {
            throw unavailable("The salon is closed on that date.");
        }
        if (!inside(booking, date, day.opens(), day.closes(), zone)) {
            throw unavailable("The booking falls outside the salon's opening hours.");
        }
        MemberWorkingDay working = schedule.on(memberId, date);
        if (!working.working()) {
            throw unavailable("The professional does not work on that date.");
        }
        boolean inOneWindow = working.windows().stream()
                .anyMatch(window -> inside(booking, date, window.starts(), window.finishes(), zone));
        if (!inOneWindow) {
            throw unavailable("The booking falls outside the professional's working time on that date.");
        }
    }

    /** Whether the booking lies wholly in the local window of the date, compared as instants. */
    static boolean inside(Occupation booking, LocalDate date, LocalTime from, LocalTime to, ZoneId zone) {
        Instant opens = SalonTime.toInstant(LocalDateTime.of(date, from), zone);
        Instant closes = SalonTime.toInstant(LocalDateTime.of(date, to), zone);
        return !booking.startsAt().isBefore(opens) && !booking.endsAt().isAfter(closes);
    }

    /** Whether the booking lies wholly in the window. */
    static boolean inside(Occupation booking, LocalDate date, TimeWindow window, ZoneId zone) {
        return inside(booking, date, window.starts(), window.finishes(), zone);
    }

    static ApplicationProblemException unavailable(String detail) {
        return new ApplicationProblemException(409, ErrorCode.SLOT_UNAVAILABLE).withDetail(detail);
    }
}
