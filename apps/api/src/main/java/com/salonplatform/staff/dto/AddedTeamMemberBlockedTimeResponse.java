package com.salonplatform.staff.dto;

import com.salonplatform.staff.service.MemberTimeOffService.AddedBlock;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** A block as added, and the bookings in it, which are kept. */
@Schema(
        name = "AddedTeamMemberBlockedTime",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"block", "affected"})
public record AddedTeamMemberBlockedTimeResponse(
        TeamBlockedTimeResponse block, List<TeamAffectedBookingResponse> affected) {

    public static AddedTeamMemberBlockedTimeResponse from(AddedBlock added) {
        return new AddedTeamMemberBlockedTimeResponse(
                TeamBlockedTimeResponse.from(added.block()),
                added.affected().stream().map(TeamAffectedBookingResponse::from).toList());
    }
}
