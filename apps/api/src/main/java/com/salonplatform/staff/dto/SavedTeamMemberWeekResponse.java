package com.salonplatform.staff.dto;

import com.salonplatform.staff.service.MemberScheduleService.SavedWeek;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** A person's week as saved, and the upcoming bookings it leaves outside their working time, which are kept. */
@Schema(
        name = "SavedTeamMemberWeek",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"version", "weekly", "affected"})
public record SavedTeamMemberWeekResponse(
        @Schema(minimum = "1") long version,
        @ArraySchema(minItems = 7, maxItems = 7) List<TeamWorkdayResponse> weekly,
        List<TeamAffectedBookingResponse> affected) {

    public static SavedTeamMemberWeekResponse from(SavedWeek saved) {
        return new SavedTeamMemberWeekResponse(
                saved.version(),
                saved.weekly().stream().map(TeamWorkdayResponse::from).toList(),
                saved.affected().stream().map(TeamAffectedBookingResponse::from).toList());
    }
}
