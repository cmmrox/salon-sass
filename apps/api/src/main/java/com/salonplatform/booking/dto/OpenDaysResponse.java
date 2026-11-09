package com.salonplatform.booking.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/** The local dates in a range on which the salon is open (F07-S02 AC-6). */
@Schema(name = "OpenDays", additionalProperties = Schema.AdditionalPropertiesValue.FALSE, requiredProperties = "days")
public record OpenDaysResponse(
        @ArraySchema(
                arraySchema = @Schema(description = "In date order."),
                schema = @Schema(type = "string", format = "date"))
        List<LocalDate> days) {}
