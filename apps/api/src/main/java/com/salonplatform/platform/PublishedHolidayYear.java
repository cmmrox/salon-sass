package com.salonplatform.platform;

import java.time.Instant;
import java.util.List;

/**
 * One published year of the holiday calendar.
 *
 * @param year the calendar year
 * @param revision how many times it has been published: 1 for the first publication
 * @param publishedAt when its latest revision was published
 * @param days its days, in date order
 */
public record PublishedHolidayYear(int year, int revision, Instant publishedAt, List<PublishedHoliday> days) {

    public PublishedHolidayYear {
        days = List.copyOf(days);
    }
}
