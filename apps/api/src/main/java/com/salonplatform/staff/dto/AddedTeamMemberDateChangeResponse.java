package com.salonplatform.staff.dto;

import com.salonplatform.staff.service.MemberScheduleService.AddedChange;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** A date change as added, and the bookings on its date it leaves outside the person's working time, which are kept. */
@Schema(
        name = "AddedTeamMemberDateChange",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"change", "affected"})
public record AddedTeamMemberDateChangeResponse(
        TeamDateChangeResponse change, List<TeamAffectedBookingResponse> affected) {

    public static AddedTeamMemberDateChangeResponse from(AddedChange added) {
        return new AddedTeamMemberDateChangeResponse(
                TeamDateChangeResponse.from(added.change()),
                added.affected().stream().map(TeamAffectedBookingResponse::from).toList());
    }
}
