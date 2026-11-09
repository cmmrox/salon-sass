package com.salonplatform.staff.dto;

import com.salonplatform.staff.service.TeamMemberService.MemberView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Everyone on the team in name order, whole: a salon has at most 200 (decision 094's exception). */
@Schema(
        name = "TeamMemberList",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"items"})
public record TeamMemberListResponse(List<TeamMemberResponse> items) {

    public static TeamMemberListResponse from(List<MemberView> views) {
        return new TeamMemberListResponse(
                views.stream().map(TeamMemberResponse::from).toList());
    }
}
