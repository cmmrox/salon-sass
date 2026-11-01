package com.salonplatform.platform.repository;

import com.salonplatform.platform.PublishedHoliday;
import com.salonplatform.platform.PublishedHolidayYear;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The published holiday calendar as the tenant role reads it: through {@code holiday_calendar_published()},
 * its one path to the calendar's tables, on which it has no grant (named direct-SQL exception 2 of
 * docs/engineering/backend.md). The function returns only published days and the year's revision and time,
 * never a draft or who published it, and no tenant table is read, so no salon needs to be set.
 */
@Component
public class PublishedHolidays {

    private static final String PUBLISHED = "SELECT year, year_revision, published_at, holiday_date, name, kind,"
            + " day_revision FROM holiday_calendar_published(?, ?)";

    private final JdbcClient jdbc;

    PublishedHolidays(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private record Row(
            int year,
            int yearRevision,
            java.time.Instant publishedAt,
            LocalDate date,
            String name,
            String kind,
            int dayRevision) {}

    /** The published years in the range, oldest first, each with its days in date order. */
    public List<PublishedHolidayYear> between(int firstYear, int lastYear) {
        List<Row> rows = jdbc.sql(PUBLISHED)
                .params(firstYear, lastYear)
                .query((row, n) -> new Row(
                        row.getInt("year"),
                        row.getInt("year_revision"),
                        PlatformTimes.instant(row.getObject("published_at")),
                        row.getObject("holiday_date", LocalDate.class),
                        row.getString("name"),
                        row.getString("kind"),
                        row.getInt("day_revision")))
                .list();
        Map<Integer, List<Row>> byYear = new LinkedHashMap<>();
        for (Row row : rows) {
            byYear.computeIfAbsent(row.year(), y -> new ArrayList<>()).add(row);
        }
        List<PublishedHolidayYear> years = new ArrayList<>();
        for (List<Row> yearRows : byYear.values()) {
            Row first = yearRows.getFirst();
            List<PublishedHoliday> days = yearRows.stream()
                    .filter(row -> row.date() != null)
                    .map(row -> new PublishedHoliday(row.date(), row.name(), row.kind(), row.dayRevision()))
                    .toList();
            years.add(new PublishedHolidayYear(first.year(), first.yearRevision(), first.publishedAt(), days));
        }
        return years;
    }
}
