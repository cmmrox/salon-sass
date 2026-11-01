package com.salonplatform.platform.service.impl;

import com.salonplatform.platform.HolidayCalendar;
import com.salonplatform.platform.PublishedHolidayYear;
import com.salonplatform.platform.repository.PublishedHolidays;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Reads through the tenant role's one path to the calendar, {@code holiday_calendar_published()}, in a single
 * statement: on its own pooled connection when the caller has no transaction open, as the contract asks.
 */
@Service
class HolidayCalendarImpl implements HolidayCalendar {

    private final PublishedHolidays published;

    HolidayCalendarImpl(PublishedHolidays published) {
        this.published = published;
    }

    @Override
    public List<PublishedHolidayYear> published(int firstYear, int lastYear) {
        if (lastYear < firstYear) {
            throw new IllegalArgumentException("the last year is before the first");
        }
        return published.between(Math.max(firstYear, FIRST_YEAR), Math.min(lastYear, LAST_YEAR));
    }
}
