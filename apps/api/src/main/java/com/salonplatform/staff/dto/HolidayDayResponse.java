package com.salonplatform.staff.dto;

import com.salonplatform.staff.domain.LocalTimes;
import com.salonplatform.staff.service.HolidayChoiceService.HolidayDay;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/** A published holiday and the salon's decision about it. */
@Schema(
        name = "SalonHolidayDay",
        requiredProperties = {"date", "name", "kind", "choice", "opens", "closes", "toReview", "past"})
public record HolidayDayResponse(
        LocalDate date,
        String name,

        @Schema(allowableValues = {"poya", "public", "bank", "mercantile"})
        String kind,

        @Schema(
                types = {"string", "null"},
                allowableValues = {"closed", "open", "special"},
                description =
                        "The salon's latest choice; null when it has made none. Not applied while" + " `toReview`.")
        String choice,

        @Schema(
                types = {"string", "null"},
                description = "Special hours' opening time, `HH:MM`.")
        String opens,

        @Schema(
                types = {"string", "null"},
                description = "Special hours' closing time, `HH:MM`.")
        String closes,

        @Schema(description = "No choice applies to the day as it is published now.")
        boolean toReview,

        @Schema(description = "The day is before the salon's today.")
        boolean past) {

    public static HolidayDayResponse from(HolidayDay day) {
        return new HolidayDayResponse(
                day.date(),
                day.name(),
                day.kind(),
                day.choice(),
                LocalTimes.text(day.opens()),
                LocalTimes.text(day.closes()),
                day.toReview(),
                day.past());
    }
}
