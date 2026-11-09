package com.salonplatform.staff.dto;

import com.salonplatform.staff.service.PublicTeamService.PublicMember;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** A person's public profile: never an account, a version or anything of a hidden person. */
@Schema(
        name = "PublicTeamMember",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"id", "name", "title", "highlights", "bio", "photoUrl", "serviceIds"})
public record PublicTeamMemberResponse(
        @Schema(format = "uuid") UUID id,
        @Schema(minLength = 2, maxLength = 40) String name,

        @Schema(
                types = {"string", "null"},
                maxLength = 40)
        String title,

        @Schema(
                types = {"string", "null"},
                maxLength = 60)
        String highlights,

        @Schema(
                types = {"string", "null"},
                maxLength = 140)
        String bio,

        @Schema(
                types = {"string", "null"},
                description = "Where their photo is served on the salon's hostname; null when they have none.")
        String photoUrl,

        @ArraySchema(
                schema = @Schema(format = "uuid"),
                arraySchema = @Schema(description = "The services bookable online that they perform; at least one."))
        List<UUID> serviceIds) {

    public static PublicTeamMemberResponse from(PublicMember member) {
        return new PublicTeamMemberResponse(
                member.id(),
                member.name(),
                member.title(),
                member.highlights(),
                member.bio(),
                TeamPhotoUrls.of(member.photoId()),
                member.serviceIds());
    }
}
