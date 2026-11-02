package com.salonplatform.staff;

import java.time.LocalTime;

/**
 * A stretch of one local date, from one local time up to another.
 *
 * @param starts when it starts, the salon's local time
 * @param finishes when it finishes, after {@code starts}; that moment itself is outside it
 */
public record TimeWindow(LocalTime starts, LocalTime finishes) {}
