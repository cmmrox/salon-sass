package com.salonplatform.staff.entity;

import com.salonplatform.staff.WeekdayHours;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

/**
 * A branch's weekly opening hours (F04-S03), from its first save: one pair of local times per weekday, both
 * null on a weekday it closes. Its {@code version} works as the settings drafts' do: a plain column moved on
 * by one by every save, under the row lock a save takes first.
 */
@Entity
@Table(name = "staff_opening_hours")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OpeningHours {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "monday_opens")
    private LocalTime mondayOpens;

    @Column(name = "monday_closes")
    private LocalTime mondayCloses;

    @Column(name = "tuesday_opens")
    private LocalTime tuesdayOpens;

    @Column(name = "tuesday_closes")
    private LocalTime tuesdayCloses;

    @Column(name = "wednesday_opens")
    private LocalTime wednesdayOpens;

    @Column(name = "wednesday_closes")
    private LocalTime wednesdayCloses;

    @Column(name = "thursday_opens")
    private LocalTime thursdayOpens;

    @Column(name = "thursday_closes")
    private LocalTime thursdayCloses;

    @Column(name = "friday_opens")
    private LocalTime fridayOpens;

    @Column(name = "friday_closes")
    private LocalTime fridayCloses;

    @Column(name = "saturday_opens")
    private LocalTime saturdayOpens;

    @Column(name = "saturday_closes")
    private LocalTime saturdayCloses;

    @Column(name = "sunday_opens")
    private LocalTime sundayOpens;

    @Column(name = "sunday_closes")
    private LocalTime sundayCloses;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** A branch's first save, which Hibernate stamps with the current salon: version 1. */
    public static OpeningHours first(UUID branchId, List<WeekdayHours> week, Instant at) {
        OpeningHours hours = new OpeningHours();
        hours.id = UUID.randomUUID();
        hours.branchId = branchId;
        hours.version = 1;
        hours.write(week);
        hours.createdAt = at;
        hours.updatedAt = at;
        return hours;
    }

    /** A later save, under the row lock: the version moves on by one. */
    public void replace(List<WeekdayHours> week, Instant at) {
        version++;
        write(week);
        updatedAt = at;
    }

    /** The seven weekdays, Monday first. */
    public List<WeekdayHours> week() {
        List<WeekdayHours> week = new ArrayList<>(7);
        week.add(new WeekdayHours(DayOfWeek.MONDAY, mondayOpens, mondayCloses));
        week.add(new WeekdayHours(DayOfWeek.TUESDAY, tuesdayOpens, tuesdayCloses));
        week.add(new WeekdayHours(DayOfWeek.WEDNESDAY, wednesdayOpens, wednesdayCloses));
        week.add(new WeekdayHours(DayOfWeek.THURSDAY, thursdayOpens, thursdayCloses));
        week.add(new WeekdayHours(DayOfWeek.FRIDAY, fridayOpens, fridayCloses));
        week.add(new WeekdayHours(DayOfWeek.SATURDAY, saturdayOpens, saturdayCloses));
        week.add(new WeekdayHours(DayOfWeek.SUNDAY, sundayOpens, sundayCloses));
        return week;
    }

    private void write(List<WeekdayHours> week) {
        for (WeekdayHours day : week) {
            switch (day.day()) {
                case MONDAY -> {
                    mondayOpens = day.opens();
                    mondayCloses = day.closes();
                }
                case TUESDAY -> {
                    tuesdayOpens = day.opens();
                    tuesdayCloses = day.closes();
                }
                case WEDNESDAY -> {
                    wednesdayOpens = day.opens();
                    wednesdayCloses = day.closes();
                }
                case THURSDAY -> {
                    thursdayOpens = day.opens();
                    thursdayCloses = day.closes();
                }
                case FRIDAY -> {
                    fridayOpens = day.opens();
                    fridayCloses = day.closes();
                }
                case SATURDAY -> {
                    saturdayOpens = day.opens();
                    saturdayCloses = day.closes();
                }
                case SUNDAY -> {
                    sundayOpens = day.opens();
                    sundayCloses = day.closes();
                }
            }
        }
    }
}
