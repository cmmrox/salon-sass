package com.salonplatform.staff.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.salonplatform.staff.service.MemberTimeOffService.BlockInput;
import io.swagger.v3.oas.annotations.media.Schema;

/** Part of one date when a person cannot be booked. */
@Schema(
        name = "AddTeamMemberBlockedTime",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"date", "starts", "finishes", "label"})
public record AddMemberBlockedTimeRequest(
        @Schema(format = "date", description = "The salon's local date: today to a year ahead.")
        String date,

        @Schema(description = "`HH:MM` on a quarter hour.") String starts,

        @Schema(description = "`HH:MM` on a quarter hour, after `starts`.")
        String finishes,

        @Schema(description = "2 to 40 characters, trimmed. Everyone who reads the team sees it.")
        String label) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }

    public BlockInput input() {
        return new BlockInput(date, starts, finishes, label);
    }
}
