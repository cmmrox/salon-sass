package com.salonplatform.staff.dto;

import com.salonplatform.staff.service.MemberTimeOffService.AddedLeave;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Leave as added, and the bookings in it, which are kept. */
@Schema(
        name = "AddedTeamMemberLeave",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"leave", "affected"})
public record AddedTeamMemberLeaveResponse(TeamLeaveResponse leave, List<TeamAffectedBookingResponse> affected) {

    public static AddedTeamMemberLeaveResponse from(AddedLeave added) {
        return new AddedTeamMemberLeaveResponse(
                TeamLeaveResponse.from(added.leave()),
                added.affected().stream().map(TeamAffectedBookingResponse::from).toList());
    }
}
