package com.salonplatform.staff.dto;

import com.salonplatform.staff.service.HolidayChoiceService.HolidayYear;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** The published holiday years from the salon's current year on. */
@Schema(
        name = "SalonHolidays",
        requiredProperties = {"years"})
public record SalonHolidaysResponse(
        @Schema(description = "Oldest first; empty when the operator has published none.")
        List<HolidayYearResponse> years) {

    public static SalonHolidaysResponse from(List<HolidayYear> years) {
        return new SalonHolidaysResponse(
                years.stream().map(HolidayYearResponse::from).toList());
    }
}
