package com.salonplatform.staff.dto;

import com.salonplatform.staff.service.OpeningHoursService.HoursSettings;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** The weekly hours as the salon's settings staff keep them. */
@Schema(
        name = "OpeningHoursSettings",
        requiredProperties = {"version", "weekly", "outsideHours"})
public record OpeningHoursSettingsResponse(
        @Schema(description = "The version a save is made from: 0 before the first save.")
        long version,

        @Schema(
                types = {"array", "null"},
                description = "Monday to Sunday; null before the first save.")
        List<OpeningWeekdayResponse> weekly,

        @Schema(description = "Upcoming bookings these hours leave outside opening time. They are kept.")
        List<AffectedBookingResponse> outsideHours) {

    public static OpeningHoursSettingsResponse from(HoursSettings settings) {
        return new OpeningHoursSettingsResponse(
                settings.version(),
                settings.weekly() == null
                        ? null
                        : settings.weekly().stream()
                                .map(OpeningWeekdayResponse::from)
                                .toList(),
                settings.outsideHours().stream()
                        .map(AffectedBookingResponse::from)
                        .toList());
    }
}
