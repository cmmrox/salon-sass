package com.salonplatform.staff.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

/** An acceptance of a year's choices, made from the version the caller read. */
@Schema(
        name = "AcceptSalonHolidays",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"version", "choices"})
public record AcceptHolidaysRequest(
        @NotNull @PositiveOrZero Long version,

        @Schema(description = "One for every day still to come that is to review; others may be included.")
        List<HolidayChoiceRequest> choices) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
