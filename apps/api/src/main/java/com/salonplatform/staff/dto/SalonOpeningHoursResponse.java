package com.salonplatform.staff.dto;

import com.salonplatform.staff.service.OpeningHoursService.PublicHours;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** The salon's public opening information. */
@Schema(
        name = "SalonOpeningHours",
        requiredProperties = {"weekly", "exceptions"})
public record SalonOpeningHoursResponse(
        @Schema(
                types = {"array", "null"},
                description = "Monday to Sunday; null before the salon first saves its hours.")
        List<OpeningWeekdayResponse> weekly,

        @Schema(description = "From the salon's today to 180 days ahead, in date order.")
        List<OpeningExceptionResponse> exceptions) {

    public static SalonOpeningHoursResponse from(PublicHours hours) {
        return new SalonOpeningHoursResponse(
                hours.weekly() == null
                        ? null
                        : hours.weekly().stream()
                                .map(OpeningWeekdayResponse::from)
                                .toList(),
                hours.exceptions().stream().map(OpeningExceptionResponse::from).toList());
    }
}
