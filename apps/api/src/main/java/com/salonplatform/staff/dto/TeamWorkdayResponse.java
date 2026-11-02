package com.salonplatform.staff.dto;

import com.salonplatform.staff.domain.LocalTimes;
import com.salonplatform.staff.domain.WorkDay;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** One weekday of a person's week: working between two times with up to five breaks, or not working. */
@Schema(
        name = "TeamWorkday",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"day", "working", "starts", "finishes", "breaks"})
public record TeamWorkdayResponse(
        @Schema(allowableValues = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"})
        String day,

        boolean working,

        @Schema(
                types = {"string", "null"},
                pattern = "^([01][0-9]|2[0-3]):(00|15|30|45)$",
                description = "When they start, local `HH:MM`; null when not working.")
        String starts,

        @Schema(
                types = {"string", "null"},
                pattern = "^([01][0-9]|2[0-3]):(00|15|30|45)$",
                description = "When they finish, local `HH:MM`, after `starts`; null when not working.")
        String finishes,

        @ArraySchema(arraySchema = @Schema(description = "In time order; empty when not working."), maxItems = 5)
        List<TeamBreakResponse> breaks) {

    public static TeamWorkdayResponse from(WorkDay day) {
        return new TeamWorkdayResponse(
                LocalTimes.name(day.day()),
                day.working(),
                LocalTimes.text(day.starts()),
                LocalTimes.text(day.finishes()),
                day.breaks().stream().map(TeamBreakResponse::from).toList());
    }
}
