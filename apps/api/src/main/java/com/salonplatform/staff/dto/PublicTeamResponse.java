package com.salonplatform.staff.dto;

import com.salonplatform.staff.service.PublicTeamService.PublicMember;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** The salon's public team in name order. */
@Schema(
        name = "PublicTeam",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"items"})
public record PublicTeamResponse(List<PublicTeamMemberResponse> items) {

    public static PublicTeamResponse from(List<PublicMember> members) {
        return new PublicTeamResponse(
                members.stream().map(PublicTeamMemberResponse::from).toList());
    }
}
