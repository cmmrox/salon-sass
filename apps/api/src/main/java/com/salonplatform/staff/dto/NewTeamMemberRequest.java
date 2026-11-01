package com.salonplatform.staff.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.salonplatform.staff.service.TeamMemberService.MemberInput;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** A new team member. */
@Schema(
        name = "NewTeamMember",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"name"})
public record NewTeamMemberRequest(
        @Schema(description = "2 to 40 characters after trimming. Two people may share a name.")
        String name,

        @Schema(
                types = {"string", "null"},
                description = "Up to 40 characters; empty or null for none.")
        String title,

        @Schema(
                types = {"string", "null"},
                description = "Up to 60 characters; empty or null for none.")
        String highlights,

        @Schema(
                types = {"string", "null"},
                description = "Up to 140 characters; empty or null for none.")
        String bio,

        @Schema(description = "Whether customers can see them for online booking; on when absent.")
        Boolean shownOnline,

        @Schema(description = "What they perform; nothing when absent.")
        TeamMemberServicesRequest services,

        @Schema(
                types = {"string", "null"},
                format = "uuid",
                description = "An active account of the salon not linked to another person; absent or null for no"
                        + " sign-in, which takes a staff seat.")
        UUID signInAccountId) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }

    public MemberInput input() {
        return new MemberInput(
                name,
                title,
                highlights,
                bio,
                shownOnline,
                services == null ? null : services.categoryIds(),
                services == null ? null : services.serviceIds(),
                signInAccountId);
    }
}
