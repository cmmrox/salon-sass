package com.salonplatform.staff.service;

import com.salonplatform.staff.AffectedBooking;
import com.salonplatform.staff.OpeningDay;
import com.salonplatform.staff.WeekdayHours;
import java.util.List;

/**
 * A salon's weekly opening hours (F04-S03): public with the coming exceptions, and kept by its settings staff
 * ({@code settings.read}, {@code settings.manage}). A save applies at once; nothing is a draft. The branch is
 * the salon's default branch (R1 has one; no screen names it).
 */
public interface OpeningHoursService {

    /** One weekday as a request sends it, validated here. */
    record DayInput(String day, Boolean open, String opens, String closes) {}

    /**
     * What anyone may see: the weekly hours (null before the salon first saves), and the exceptions from the
     * salon's today to 180 days ahead: closed days and holidays with special hours, never a closure's reason.
     */
    record PublicHours(List<WeekdayHours> weekly, List<OpeningDay> exceptions) {}

    /**
     * The hours as settings staff keep them: the version a save is made from (0 before the first save), the
     * weekly hours (null before the first save), and the upcoming bookings they leave outside opening time.
     */
    record HoursSettings(long version, List<WeekdayHours> weekly, List<AffectedBooking> outsideHours) {}

    PublicHours publicHours();

    HoursSettings settings();

    /**
     * Saves the week: each of the seven weekdays once, open between two quarter-hour times, closing after
     * opening, or closed. 400 naming each bad field; 409 {@code conflict} when the hours changed since
     * {@code version}. Recorded in the salon's audit trail with the weekdays it changed.
     */
    HoursSettings save(Long version, List<DayInput> weekly);
}
