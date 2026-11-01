package com.salonplatform.platform.repository;

import static com.salonplatform.platform.repository.PlatformTimes.instant;
import static com.salonplatform.platform.repository.PlatformTimes.time;

import com.salonplatform.platform.domain.HolidayDays.Day;
import com.salonplatform.shared.persistence.PlatformDatabase;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The holiday calendar's tables ({@code platform_holiday_year}, {@code platform_holiday_draft_day},
 * {@code platform_holiday_published_day}), on the platform connection: named direct-SQL exception 1 of
 * docs/engineering/backend.md, platform tables with no {@code tenant_id}. Every change runs inside a platform
 * transaction that holds the year's row lock ({@link #lockYear}), so imports, edits and publications of one
 * year happen one at a time.
 */
@Component
public class HolidayCalendarStore {

    /** A year of the calendar, with how many days its draft has. */
    public record Year(
            int year,
            long version,
            int revision,
            Instant publishedAt,
            UUID publishedBy,
            int draftDays,
            Instant createdAt,
            Instant updatedAt) {}

    /** A published day and the revision that last added or changed it. */
    public record PublishedDay(LocalDate date, String name, String kind, int revision) {}

    private static final String YEAR_COLUMNS = "y.year, y.version, y.revision, y.published_at, y.published_by,"
            + " (SELECT count(*) FROM platform_holiday_draft_day d WHERE d.year = y.year) AS draft_days,"
            + " y.created_at, y.updated_at";

    private final PlatformDatabase database;

    HolidayCalendarStore(PlatformDatabase database) {
        this.database = database;
    }

    /** Every year that has been started, newest first. */
    public List<Year> years() {
        return database.jdbc()
                .sql("SELECT " + YEAR_COLUMNS + " FROM platform_holiday_year y ORDER BY y.year DESC")
                .query((row, n) -> year(row))
                .list();
    }

    public Optional<Year> findYear(int year) {
        return database
                .jdbc()
                .sql("SELECT " + YEAR_COLUMNS + " FROM platform_holiday_year y WHERE y.year = ?")
                .param(year)
                .query((row, n) -> year(row))
                .list()
                .stream()
                .findFirst();
    }

    /** Starts the year, empty and unpublished, unless it is started already. */
    public void startYear(int year, Instant at) {
        database.requireTransaction();
        database.jdbc().sql("""
                        INSERT INTO platform_holiday_year (year, version, revision, published_at, published_by,
                            created_at, updated_at)
                        VALUES (?, 0, 0, NULL, NULL, ?, ?)
                        ON CONFLICT (year) DO NOTHING
                        """).params(year, time(at), time(at)).update();
    }

    /** The year, held for update until the transaction ends. */
    public Optional<Year> lockYear(int year) {
        database.requireTransaction();
        return database
                .jdbc()
                .sql("SELECT " + YEAR_COLUMNS + " FROM platform_holiday_year y WHERE y.year = ? FOR UPDATE OF y")
                .param(year)
                .query((row, n) -> year(row))
                .list()
                .stream()
                .findFirst();
    }

    /** The draft's days, in date order. */
    public List<Day> draftDays(int year) {
        return database.jdbc()
                .sql("SELECT holiday_date, name, kind FROM platform_holiday_draft_day WHERE year = ?"
                        + " ORDER BY holiday_date")
                .param(year)
                .query((row, n) -> new Day(
                        row.getObject("holiday_date", LocalDate.class), row.getString("name"), row.getString("kind")))
                .list();
    }

    /** The published days, in date order. */
    public List<PublishedDay> publishedDays(int year) {
        return database.jdbc()
                .sql("SELECT holiday_date, name, kind, revision FROM platform_holiday_published_day WHERE year = ?"
                        + " ORDER BY holiday_date")
                .param(year)
                .query((row, n) -> new PublishedDay(
                        row.getObject("holiday_date", LocalDate.class),
                        row.getString("name"),
                        row.getString("kind"),
                        row.getInt("revision")))
                .list();
    }

    /** Replaces every day of the draft. */
    public void replaceDraft(int year, List<Day> days, Instant at) {
        database.requireTransaction();
        database.jdbc()
                .sql("DELETE FROM platform_holiday_draft_day WHERE year = ?")
                .param(year)
                .update();
        for (Day day : days) {
            addDraftDay(year, day, at);
        }
    }

    /** Adds a day to the draft; false when the draft has that date already. */
    public boolean addDraftDay(int year, Day day, Instant at) {
        database.requireTransaction();
        return database.jdbc()
                        .sql("""
                                INSERT INTO platform_holiday_draft_day (year, holiday_date, name, kind, created_at,
                                    updated_at)
                                VALUES (?, ?, ?, ?, ?, ?)
                                ON CONFLICT (year, holiday_date) DO NOTHING
                                """)
                        .params(year, Date.valueOf(day.date()), day.name(), day.kind(), time(at), time(at))
                        .update()
                > 0;
    }

    /** Changes a draft day's name and kind; false when the draft has no such date. */
    public boolean changeDraftDay(int year, Day day, Instant at) {
        database.requireTransaction();
        return database.jdbc()
                        .sql("UPDATE platform_holiday_draft_day SET name = ?, kind = ?, updated_at = ?"
                                + " WHERE year = ? AND holiday_date = ?")
                        .params(day.name(), day.kind(), time(at), year, Date.valueOf(day.date()))
                        .update()
                > 0;
    }

    /** Removes a draft day; false when the draft has no such date. */
    public boolean removeDraftDay(int year, LocalDate date) {
        database.requireTransaction();
        return database.jdbc()
                        .sql("DELETE FROM platform_holiday_draft_day WHERE year = ? AND holiday_date = ?")
                        .params(year, Date.valueOf(date))
                        .update()
                > 0;
    }

    /** Records a change to the year's draft: its version moves on by one. */
    public void draftChanged(int year, Instant at) {
        database.requireTransaction();
        database.jdbc()
                .sql("UPDATE platform_holiday_year SET version = version + 1, updated_at = ? WHERE year = ?")
                .params(time(at), year)
                .update();
    }

    /**
     * Publishes the draft as the given revision: the published days become the draft's, an added or changed
     * day carrying the new revision and an unchanged one keeping its own.
     */
    public void publish(
            int year,
            int revision,
            UUID by,
            List<Day> added,
            List<Day> changed,
            List<LocalDate> withdrawn,
            Instant at) {
        database.requireTransaction();
        for (LocalDate date : withdrawn) {
            database.jdbc()
                    .sql("DELETE FROM platform_holiday_published_day WHERE year = ? AND holiday_date = ?")
                    .params(year, Date.valueOf(date))
                    .update();
        }
        for (Day day : changed) {
            database.jdbc()
                    .sql("UPDATE platform_holiday_published_day SET name = ?, kind = ?, revision = ?, updated_at = ?"
                            + " WHERE year = ? AND holiday_date = ?")
                    .params(day.name(), day.kind(), revision, time(at), year, Date.valueOf(day.date()))
                    .update();
        }
        for (Day day : added) {
            database.jdbc()
                    .sql("""
                            INSERT INTO platform_holiday_published_day (year, holiday_date, name, kind, revision,
                                created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(year, Date.valueOf(day.date()), day.name(), day.kind(), revision, time(at), time(at))
                    .update();
        }
        database.jdbc()
                .sql("UPDATE platform_holiday_year SET revision = ?, version = version + 1, published_at = ?,"
                        + " published_by = ?, updated_at = ? WHERE year = ?")
                .params(revision, time(at), by, time(at), year)
                .update();
    }

    private static Year year(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Year(
                row.getInt("year"),
                row.getLong("version"),
                row.getInt("revision"),
                instant(row.getObject("published_at")),
                row.getObject("published_by", UUID.class),
                row.getInt("draft_days"),
                instant(row.getObject("created_at")),
                instant(row.getObject("updated_at")));
    }
}
