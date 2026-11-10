package com.salonplatform.booking.domain;

import com.salonplatform.catalogue.SegmentKind;
import com.salonplatform.catalogue.ServiceSegment;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The time a booking takes and the part of it that occupies its professional (decision 074, AC-4): the segments run
 * one after another from the start; active and buffer segments occupy the professional, a processing segment (F17)
 * does not. Adjacent occupying segments make one range, so an R1 service (active, then a buffer) occupies one range
 * from its start to its end. Ranges are half-open: a range's end is free.
 */
public final class Occupation {

    /** One stretch of time the professional is occupied, half-open: {@code to} itself is free. */
    public record Range(Instant from, Instant to) {

        /** Whether the two share any moment (touching ranges do not). */
        public boolean overlaps(Range other) {
            return from.isBefore(other.to) && other.from.isBefore(to);
        }
    }

    private final Instant startsAt;
    private final Instant endsAt;
    private final List<Range> ranges;

    private Occupation(Instant startsAt, Instant endsAt, List<Range> ranges) {
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.ranges = List.copyOf(ranges);
    }

    public static Occupation of(Instant startsAt, List<ServiceSegment> segments) {
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("A service has at least one segment");
        }
        List<Range> ranges = new ArrayList<>();
        Instant at = startsAt;
        for (ServiceSegment segment : segments) {
            Instant next = at.plusSeconds(60L * segment.minutes());
            if (segment.kind() != SegmentKind.PROCESSING) {
                if (!ranges.isEmpty() && ranges.getLast().to().equals(at)) {
                    ranges.set(ranges.size() - 1, new Range(ranges.getLast().from(), next));
                } else {
                    ranges.add(new Range(at, next));
                }
            }
            at = next;
        }
        if (ranges.isEmpty()) {
            throw new IllegalArgumentException("A booking occupies its professional for some of its time");
        }
        return new Occupation(startsAt, at, ranges);
    }

    /** When the first segment starts. */
    public Instant startsAt() {
        return startsAt;
    }

    /** When the last segment ends. */
    public Instant endsAt() {
        return endsAt;
    }

    /** The ranges that occupy the professional, in order, adjacent ones merged. */
    public List<Range> ranges() {
        return ranges;
    }

    /** Whether any range of this booking overlaps any of {@code others}. */
    public boolean overlapsAny(List<Range> others) {
        return ranges.stream().anyMatch(mine -> others.stream().anyMatch(mine::overlaps));
    }

    /** The occupied ranges as PostgreSQL's {@code tstzmultirange} literal, in UTC, in order. */
    public String literal() {
        DateTimeFormatter instant = DateTimeFormatter.ISO_INSTANT;
        return ranges.stream()
                .map(range -> "[\"" + instant.format(range.from()) + "\",\"" + instant.format(range.to()) + "\")")
                .collect(Collectors.joining(",", "{", "}"));
    }
}
