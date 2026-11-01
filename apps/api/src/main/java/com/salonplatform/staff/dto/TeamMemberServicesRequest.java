package com.salonplatform.staff.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** What a person performs: whole categories, and single services outside them. */
@Schema(name = "TeamMemberServices", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record TeamMemberServicesRequest(
        @ArraySchema(
                schema = @Schema(format = "uuid"),
                arraySchema = @Schema(description = "Categories performed whole, with any service added later."))
        List<UUID> categoryIds,

        @ArraySchema(
                schema = @Schema(format = "uuid"),
                arraySchema = @Schema(description = "Single services, none inside a category also sent whole."))
        List<UUID> serviceIds) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused("services." + field);
    }
}
