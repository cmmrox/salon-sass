package com.salonplatform.staff.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

/** A save of the weekly hours, made from the version the caller read. */
@Schema(
        name = "SaveOpeningHours",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"version", "weekly"})
public record SaveOpeningHoursRequest(
        @NotNull @PositiveOrZero Long version,

        @Schema(description = "Each of the seven weekdays once.")
        List<OpeningWeekdayRequest> weekly) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
