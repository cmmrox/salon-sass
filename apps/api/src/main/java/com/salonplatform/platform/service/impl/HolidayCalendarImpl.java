package com.salonplatform.platform.service.impl;

import com.salonplatform.platform.HolidayCalendar;
import com.salonplatform.platform.PublishedHolidayYear;
import com.salonplatform.platform.repository.PublishedHolidays;
import com.salonplatform.shared.persistence.ReadOnlyWork;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Reads through the tenant role's one path to the calendar, {@code holiday_calendar_published()}, in a single
 * statement, in a read-only transaction of its own ({@link ReadOnlyWork}).
 */
@Service
class HolidayCalendarImpl implements HolidayCalendar {

    private final PublishedHolidays published;

    private final ReadOnlyWork reading;

    HolidayCalendarImpl(PublishedHolidays published, ReadOnlyWork reading) {
        this.published = published;
        this.reading = reading;
    }

    @Override
    public List<PublishedHolidayYear> published(int firstYear, int lastYear) {
        if (lastYear < firstYear) {
            throw new IllegalArgumentException("the last year is before the first");
        }
        return reading.read(() -> published.between(Math.max(firstYear, FIRST_YEAR), Math.min(lastYear, LAST_YEAR)));
    }
}
