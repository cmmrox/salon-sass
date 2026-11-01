package com.salonplatform.platform;

import java.util.List;

/**
 * The published Sri Lankan holiday and Poya calendar (STF-04, decisions 078 and 088), for a module that lets
 * a salon decide what it does on each holiday ({@code staff}). Only what the operator has published is seen:
 * never a draft, and never who published it.
 *
 * <p>The calendar is platform reference data with no salon in it, so no salon is a parameter and none is
 * needed. The tenant role has no grant on the calendar's tables; the implementation reads the published days
 * through the narrow {@code holiday_calendar_published()} database function (docs/architecture/multi-tenancy.md,
 * "Platform data and operator access"). Each call is one short read on its own pooled connection, outside
 * the caller's transaction when the caller has none open; call it before opening one.
 */
public interface HolidayCalendar {

    /** The earliest and latest year the operator can keep a calendar for. */
    int FIRST_YEAR = 2000;

    int LAST_YEAR = 2100;

    /**
     * The published years from {@code firstYear} to {@code lastYear}, oldest first, each with its days in date
     * order. A year never published is absent; a published year whose days a later revision all withdrew is
     * present with no days.
     *
     * @throws IllegalArgumentException when {@code lastYear} is before {@code firstYear}
     */
    List<PublishedHolidayYear> published(int firstYear, int lastYear);
}
