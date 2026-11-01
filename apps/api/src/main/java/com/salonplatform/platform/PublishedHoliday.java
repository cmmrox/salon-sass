package com.salonplatform.platform;

import java.time.LocalDate;

/**
 * One published holiday. A salon's decision about it is made against its {@code revision}: the revision that
 * last added or changed this day. While the day keeps that revision the decision stands; a revision that
 * changes its name or kind gives it a new one, and a salon reviews it again.
 *
 * @param date the day, a local date in Sri Lanka
 * @param name its name as the gazette gives it
 * @param kind {@code poya}, {@code public}, {@code bank} or {@code mercantile}
 * @param revision the revision that last added or changed it
 */
public record PublishedHoliday(LocalDate date, String name, String kind, int revision) {}
