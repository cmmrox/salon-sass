package com.salonplatform.staff.service.impl;

import com.salonplatform.platform.PublishedHoliday;
import com.salonplatform.staff.OpeningDay;
import com.salonplatform.staff.WeekdayHours;
import com.salonplatform.staff.domain.OpeningRules;
import com.salonplatform.staff.domain.OpeningRules.AppliedChoice;
import com.salonplatform.staff.entity.Closure;
import com.salonplatform.staff.entity.HolidayChoice;
import com.salonplatform.staff.entity.OpeningHours;
import com.salonplatform.staff.repository.ClosureRepository;
import com.salonplatform.staff.repository.HolidayChoiceRepository;
import com.salonplatform.staff.repository.OpeningHoursRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * A branch's opening on each date of a range, from its weekly hours, the holiday choices that apply and its
 * closures ({@link OpeningRules}). Three queries for the whole range, inside the caller's tenant transaction;
 * the published holidays are given, read before the transaction.
 */
@Component
class OpeningCalendar {

    private final OpeningHoursRepository hours;
    private final HolidayChoiceRepository choices;
    private final ClosureRepository closures;

    OpeningCalendar(OpeningHoursRepository hours, HolidayChoiceRepository choices, ClosureRepository closures) {
        this.hours = hours;
        this.choices = choices;
        this.closures = closures;
    }

    List<OpeningDay> between(UUID branch, LocalDate from, LocalDate to, Map<LocalDate, PublishedHoliday> holidays) {
        Map<DayOfWeek, WeekdayHours> week =
                hours.findByBranch(branch).map(OpeningCalendar::byDay).orElse(null);
        Map<LocalDate, HolidayChoice> chosen = choices.findBetween(branch, from, to).stream()
                .collect(Collectors.toMap(HolidayChoice::getHolidayDate, choice -> choice));
        Set<LocalDate> closed = closures.findBetween(branch, from, to).stream()
                .map(Closure::getClosureDate)
                .collect(Collectors.toSet());
        List<OpeningDay> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            PublishedHoliday holiday = holidays.get(date);
            days.add(OpeningRules.decide(
                    date,
                    week,
                    applied(holiday, chosen.get(date)),
                    closed.contains(date),
                    holiday == null ? null : holiday.name()));
        }
        return days;
    }

    /** The choice for a published day, when it was made against the day as it is published now. */
    static AppliedChoice applied(PublishedHoliday holiday, HolidayChoice choice) {
        if (holiday == null || choice == null || !choice.madeAgainst(holiday.revision())) {
            return null;
        }
        return new AppliedChoice(choice.kind(), choice.getOpens(), choice.getCloses());
    }

    static Map<DayOfWeek, WeekdayHours> byDay(OpeningHours saved) {
        Map<DayOfWeek, WeekdayHours> week = new EnumMap<>(DayOfWeek.class);
        for (WeekdayHours day : saved.week()) {
            week.put(day.day(), day);
        }
        return week;
    }
}
