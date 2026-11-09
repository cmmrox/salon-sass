package com.salonplatform.staff.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** What a person performs as it reads now: ids of what no longer exists are left out, so it can be sent back. */
@Schema(
        name = "TeamMemberPerformedServices",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"categoryIds", "serviceIds"})
public record TeamMemberServicesResponse(
        @ArraySchema(schema = @Schema(format = "uuid")) List<UUID> categoryIds,
        @ArraySchema(schema = @Schema(format = "uuid")) List<UUID> serviceIds) {}
