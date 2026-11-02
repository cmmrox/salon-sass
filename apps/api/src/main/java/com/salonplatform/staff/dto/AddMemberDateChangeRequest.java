package com.salonplatform.staff.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.salonplatform.staff.service.MemberScheduleService.ChangeInput;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** A change to a person's hours for one date. */
@Schema(
        name = "AddTeamMemberDateChange",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"date", "working"})
public record AddMemberDateChangeRequest(
        @Schema(format = "date", description = "The salon's local date: today to a year ahead.")
        String date,

        Boolean working,

        @Schema(
                types = {"string", "null"},
                description = "`HH:MM` on a quarter hour when working; null when not.")
        String starts,

        @Schema(
                types = {"string", "null"},
                description = "`HH:MM` on a quarter hour after `starts` when working; null when not.")
        String finishes,

        @Schema(description = "As on a weekday. A date not worked keeps none, whatever is sent.")
        List<TeamBreakRequest> breaks,

        @Schema(
                types = {"string", "null"},
                maxLength = 60,
                description = "Only those who can change the team see it; up to 60 characters, empty or null for none.")
        String note) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }

    public ChangeInput input() {
        return new ChangeInput(date, working, starts, finishes, SaveMemberWeekRequest.breaks(breaks), note);
    }
}
