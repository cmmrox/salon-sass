package com.salonplatform.staff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.salonplatform.staff.service.TeamMemberService.NextLeave;
import io.swagger.v3.oas.annotations.media.Schema;

/** A person's current or next leave, as their card shows it. Its type is only for a caller with {@code team.manage}. */
@Schema(
        name = "TeamNextLeave",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"firstDay", "lastDay"})
public record TeamNextLeaveResponse(
        @Schema(format = "date", description = "The first day, the salon's local date.")
        String firstDay,

        @Schema(format = "date", description = "The last day, on or after `firstDay`.")
        String lastDay,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(
                allowableValues = {"annual", "sick", "personal", "other"},
                description = "Its type. Only for a caller with `team.manage`.")
        String type) {

    static TeamNextLeaveResponse from(NextLeave next) {
        return next == null
                ? null
                : new TeamNextLeaveResponse(
                        next.firstDay().toString(),
                        next.lastDay().toString(),
                        next.type() == null ? null : next.type().value());
    }
}
