package com.salonplatform.shared.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * The application's time: "now" from its one {@link Clock}, and the conversion between an instant,
 * which is what is stored ({@code timestamptz}, BKG-15), and a salon's local date and time, which is
 * what is shown.
 *
 * <p>The rules when the salon's clocks change:
 * <ul>
 * <li>An instant always has exactly one local date and time.</li>
 * <li>A local time in the hour that is skipped (London, 01:30 on the last Sunday of March) does not
 * exist. It is moved forward by the length of the gap: 01:30 becomes 02:30, the same instant as
 * 01:30 GMT.</li>
 * <li>A local time in the hour that repeats (London, 01:30 on the last Sunday of October) happens
 * twice. The earlier of the two instants is used: 01:30 BST, not 01:30 GMT.</li>
 * </ul>
 *
 * <p>Every time the application decides on (when something is due, when it expires, when it
 * happened) is {@link #now()}, never the database's {@code now()}.
 */
public final class SalonTime {

    /**
     * A local date and time with its offset, to the second, the offset always written {@code +HH:MM}
     * (never {@code Z}): {@code 2026-03-29T02:30:00+01:00}.
     */
    public static final DateTimeFormatter LOCAL_WITH_OFFSET = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx");

    private final Clock clock;

    public SalonTime(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The application's current instant. */
    public Instant now() {
        return clock.instant();
    }

    /** The local date and time in the zone at that instant. */
    public static ZonedDateTime toLocal(Instant instant, ZoneId zone) {
        return instant.atZone(zone);
    }

    /**
     * The instant of a local date and time in the zone, by the rules above for the hour that is
     * skipped and the hour that repeats.
     */
    public static Instant toInstant(LocalDateTime local, ZoneId zone) {
        // ofLocal with no preferred offset does exactly this: a time in a gap moves forward by the
        // gap's length, and a time in an overlap takes the earlier offset.
        return ZonedDateTime.ofLocal(local, zone, null).toInstant();
    }

    /** {@link #LOCAL_WITH_OFFSET} of the instant in the zone, to the second (truncated). */
    public static String formatLocal(Instant instant, ZoneId zone) {
        return LOCAL_WITH_OFFSET.format(toLocal(instant.truncatedTo(ChronoUnit.SECONDS), zone));
    }
}
