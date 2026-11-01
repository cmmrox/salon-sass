package com.salonplatform.staff.dto;

import com.salonplatform.staff.service.HolidayChoiceService.HolidayYear;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** A published holiday year and the salon's review of it. */
@Schema(
        name = "SalonHolidayYear",
        requiredProperties = {"year", "revision", "publishedAt", "reviewed", "version", "days"})
public record HolidayYearResponse(
        int year,

        @Schema(description = "The operator's latest revision of the year.")
        int revision,

        Instant publishedAt,

        @Schema(description = "The salon accepted its choices, and no day still to come is to review.")
        boolean reviewed,

        @Schema(description = "The version an acceptance is made from: 0 before the first.")
        long version,

        @Schema(description = "Every published day of the year, in date order.")
        List<HolidayDayResponse> days) {

    public static HolidayYearResponse from(HolidayYear year) {
        return new HolidayYearResponse(
                year.year(),
                year.revision(),
                year.publishedAt(),
                year.reviewed(),
                year.version(),
                year.days().stream().map(HolidayDayResponse::from).toList());
    }
}
