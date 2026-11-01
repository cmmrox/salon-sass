package com.salonplatform.staff.dto;

import com.salonplatform.staff.service.MemberScheduleService.ScheduleView;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** A person's schedule. */
@Schema(
        name = "TeamMemberSchedule",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"version", "today", "weekly", "dateChanges"})
public record TeamMemberScheduleResponse(
        @Schema(minimum = "0", description = "The week's version, which a save is made from; 0 when never saved.")
        long version,

        @Schema(format = "date", description = "The salon's local date now: the first a date change may have.")
        String today,

        @ArraySchema(
                arraySchema = @Schema(description = "Each of the seven weekdays, Monday first."),
                minItems = 7,
                maxItems = 7)
        List<TeamWorkdayResponse> weekly,

        @ArraySchema(arraySchema = @Schema(description = "The changes from `today` on, in date order; at most 100."))
        List<TeamDateChangeResponse> dateChanges) {

    public static TeamMemberScheduleResponse from(ScheduleView view) {
        return new TeamMemberScheduleResponse(
                view.version(),
                view.today().toString(),
                view.weekly().stream().map(TeamWorkdayResponse::from).toList(),
                view.dateChanges().stream().map(TeamDateChangeResponse::from).toList());
    }
}
