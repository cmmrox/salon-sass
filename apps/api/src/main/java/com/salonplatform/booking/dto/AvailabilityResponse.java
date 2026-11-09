package com.salonplatform.booking.dto;

import com.salonplatform.booking.Availability.Answer;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/** The free start times of a service on one local date (F07-S02). */
@Schema(
        name = "Availability",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"date", "times", "reason"})
public record AvailabilityResponse(
        @Schema(description = "The salon's local date asked for.")
        LocalDate date,

        @ArraySchema(arraySchema = @Schema(description = "In time order; each time once."))
        List<AvailableTimeResponse> times,

        @Schema(
                types = {"string", "null"},
                allowableValues = {"closed", "nobody_performs", "not_working", "fully_booked"},
                description = "Why `times` is empty: the salon is closed that date, nobody performs the service, the"
                        + " professional (or every eligible one) works no time that date, or no booking fits in what"
                        + " is free. Null when there are times.")
        String reason) {

    public static AvailabilityResponse from(Answer answer, ZoneId zone) {
        return new AvailabilityResponse(
                answer.date(),
                answer.times().stream()
                        .map(time -> AvailableTimeResponse.from(time, zone))
                        .toList(),
                answer.reason() == null ? null : answer.reason().name().toLowerCase(Locale.ROOT));
    }
}
