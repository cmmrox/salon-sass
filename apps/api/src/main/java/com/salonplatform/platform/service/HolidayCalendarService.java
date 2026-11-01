package com.salonplatform.platform.service;

import com.salonplatform.platform.dto.HolidayYearDetail;
import com.salonplatform.platform.dto.HolidayYearList;

/**
 * The operator's holiday calendar (docs/architecture/operator-console.md, "Holiday calendar"): each year a
 * draft, kept by importing a CSV file or by single days, then published to every salon as a revision. Admins
 * change it; Support operators only read it. Every import, edit and publication is in the platform audit.
 * Years run from 2000 to 2100; any other year is not found.
 */
public interface HolidayCalendarService {

    /** A day as an Admin sends it, validated here, after authorization. */
    record DayInput(String date, String name, String kind) {}

    /** Every year started, newest first. */
    HolidayYearList years();

    /**
     * A year's draft against what salons have, with the counts a publication would add, change and withdraw.
     *
     * @throws com.salonplatform.shared.web.NotFoundException when the year has not been started
     */
    HolidayYearDetail year(int year);

    /**
     * Replaces the draft's days with a CSV file's, starting the year when needed. Any bad line refuses the
     * whole file, 400 with one error per bad line, and nothing changes.
     */
    HolidayYearDetail importFile(int year, byte[] file);

    /** Adds one day to the draft, starting the year when needed. 409 {@code conflict} when the date is there. */
    HolidayYearDetail addDay(int year, DayInput day);

    /** Changes a draft day's name and kind. Not found when the draft has no such date. */
    HolidayYearDetail changeDay(int year, String date, DayInput day);

    /** Removes a day from the draft. Not found when the draft has no such date. */
    HolidayYearDetail removeDay(int year, String date);

    /**
     * Publishes the draft as the year's next revision. 409 {@code conflict} when the year changed since
     * {@code version}; 409 {@code invalid_state} when the draft adds, changes and withdraws nothing.
     */
    HolidayYearDetail publish(int year, long version);
}
