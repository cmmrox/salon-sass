package com.salonplatform.staff.service;

import com.salonplatform.staff.AffectedBooking;
import com.salonplatform.staff.TimeWindow;
import com.salonplatform.staff.domain.WorkDay;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * A team member's schedule as the salon's staff keep it (STF-02, decision 102): their weekly hours with breaks, and their
 * changes for single dates. Reading needs {@code team.read}, and every change {@code team.manage}. A date change's note
 * is for a caller with {@code team.manage} only.
 */
public interface MemberScheduleService {

    /** A break as a request sends it; nothing here has been checked. */
    record BreakInput(String starts, String finishes) {}

    /** A weekday as a request sends it; nothing here has been checked. */
    record DayInput(String day, Boolean working, String starts, String finishes, List<BreakInput> breaks) {}

    /** A date change as a request sends it; nothing here has been checked. */
    record ChangeInput(
            String date, Boolean working, String starts, String finishes, List<BreakInput> breaks, String note) {}

    /**
     * A date change as a caller may see it.
     *
     * @param starts when they start, or null when the change is that they do not work
     * @param finishes when they finish, or null likewise
     * @param noteVisible whether the caller may see the note, with {@code team.manage}
     * @param note the note, or null for none or when the caller may not see it
     */
    record ChangeView(
            UUID id,
            LocalDate date,
            LocalTime starts,
            LocalTime finishes,
            List<TimeWindow> breaks,
            boolean noteVisible,
            String note) {}

    /**
     * A person's schedule.
     *
     * @param version the week's version, which a save is made from; 0 when it has never been saved
     * @param today the salon's local date now, from which date changes are listed and may be added
     * @param weekly the seven weekdays, Monday first
     * @param dateChanges the changes from today on, in date order
     */
    record ScheduleView(long version, LocalDate today, List<WorkDay> weekly, List<ChangeView> dateChanges) {}

    /** The week as saved, and the upcoming bookings it leaves outside the person's working time. */
    record SavedWeek(long version, List<WorkDay> weekly, List<AffectedBooking> affected) {}

    /** The change as added, and the bookings on its date it leaves outside the person's working time. */
    record AddedChange(ChangeView change, List<AffectedBooking> affected) {}

    /** The schedule of a person still on the team. 404 for any other id. */
    ScheduleView read(UUID memberId);

    /**
     * Saves the whole week, from the version the caller read. Leaves date changes alone, and never moves a booking.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 400 naming each field, nothing saved; 404; 409
     *     {@code conflict} for a stale version; 409 {@code invalid_state} when nothing changes
     */
    SavedWeek saveWeek(UUID memberId, Long version, List<DayInput> weekly);

    /**
     * Adds a change for one date, today to a year ahead by the salon's date. Never moves a booking.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 400 naming each field, nothing saved; 404; 409
     *     {@code conflict} when the date has a change; 409 {@code limit_reached} at 100 upcoming changes
     */
    AddedChange addDateChange(UUID memberId, ChangeInput input);

    /**
     * Removes a change that has not passed: the date is back to the weekly hours.
     *
     * @throws com.salonplatform.shared.web.NotFoundException when it has passed, is gone, or is not this person's
     */
    void removeDateChange(UUID memberId, UUID changeId);
}
