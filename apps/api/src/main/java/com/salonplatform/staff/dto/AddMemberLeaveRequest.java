package com.salonplatform.staff.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.salonplatform.staff.service.MemberTimeOffService.LeaveInput;
import io.swagger.v3.oas.annotations.media.Schema;

/** Leave for a person, as whole days. */
@Schema(
        name = "AddTeamMemberLeave",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"firstDay", "lastDay", "type"})
public record AddMemberLeaveRequest(
        @Schema(format = "date", description = "The salon's local date: today to a year ahead.")
        String firstDay,

        @Schema(format = "date", description = "On or after `firstDay`, and at most a year ahead.")
        String lastDay,

        @Schema(allowableValues = {"annual", "sick", "personal", "other"})
        String type,

        @Schema(
                types = {"string", "null"},
                maxLength = 60,
                description = "Only those who can change the team see it; up to 60 characters, empty or null for none.")
        String note) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }

    public LeaveInput input() {
        return new LeaveInput(firstDay, lastDay, type, note);
    }
}
