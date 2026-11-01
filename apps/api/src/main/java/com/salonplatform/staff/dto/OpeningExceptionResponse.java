package com.salonplatform.staff.dto;

import com.salonplatform.staff.OpeningDay;
import com.salonplatform.staff.domain.LocalTimes;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/** A coming day that differs from the weekly hours: closed, or a holiday open for special hours. */
@Schema(
        name = "OpeningException",
        requiredProperties = {"date", "kind", "opens", "closes", "holidayName"})
public record OpeningExceptionResponse(
        @Schema(description = "The salon's local date.") LocalDate date,
        @Schema(allowableValues = {"closed", "special"}) String kind,

        @Schema(
                types = {"string", "null"},
                pattern = "^([01][0-9]|2[0-3]):[0-5][0-9]$",
                description = "Special hours' local opening time; null when closed.")
        String opens,

        @Schema(
                types = {"string", "null"},
                pattern = "^([01][0-9]|2[0-3]):[0-5][0-9]$",
                description = "Special hours' local closing time; null when closed.")
        String closes,

        @Schema(
                types = {"string", "null"},
                description = "The public holiday on the date, or null.")
        String holidayName) {

    public static OpeningExceptionResponse from(OpeningDay day) {
        return new OpeningExceptionResponse(
                day.date(),
                day.open() ? "special" : "closed",
                LocalTimes.text(day.opens()),
                LocalTimes.text(day.closes()),
                day.holidayName());
    }
}
