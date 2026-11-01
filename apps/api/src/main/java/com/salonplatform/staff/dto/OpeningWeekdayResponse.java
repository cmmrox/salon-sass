package com.salonplatform.staff.dto;

import com.salonplatform.staff.WeekdayHours;
import com.salonplatform.staff.domain.LocalTimes;
import io.swagger.v3.oas.annotations.media.Schema;

/** One weekday of the weekly hours. */
@Schema(
        name = "OpeningWeekday",
        requiredProperties = {"day", "open", "opens", "closes"})
public record OpeningWeekdayResponse(
        @Schema(allowableValues = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"})
        String day,

        boolean open,

        @Schema(
                types = {"string", "null"},
                pattern = "^([01][0-9]|2[0-3]):[0-5][0-9]$",
                description = "The local opening time, `HH:MM`; null when closed.")
        String opens,

        @Schema(
                types = {"string", "null"},
                pattern = "^([01][0-9]|2[0-3]):[0-5][0-9]$",
                description = "The local closing time, after `opens`; null when closed.")
        String closes) {

    public static OpeningWeekdayResponse from(WeekdayHours day) {
        return new OpeningWeekdayResponse(
                LocalTimes.name(day.day()),
                day.opens() != null,
                LocalTimes.text(day.opens()),
                LocalTimes.text(day.closes()));
    }
}
