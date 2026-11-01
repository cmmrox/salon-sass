package com.salonplatform.staff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.salonplatform.staff.service.TeamMemberService.MemberView;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A person on the team. The two sign-in fields exist only for a caller with {@code staff.list} (decision 100): a null
 * {@link Optional} leaves the field out, and an empty one writes {@code null} for a person with no sign-in.
 */
@Schema(
        name = "TeamMember",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {
            "id",
            "name",
            "title",
            "highlights",
            "bio",
            "shownOnline",
            "photoId",
            "photoUrl",
            "services",
            "weekly",
            "version"
        })
public record TeamMemberResponse(
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

        boolean shownOnline,

        @Schema(
                types = {"string", "null"},
                format = "uuid",
                description = "Their photo; null when they have none.")
        UUID photoId,

        @Schema(
                types = {"string", "null"},
                description = "Where their photo is served on the salon's hostname; null when they have none.")
        String photoUrl,

        TeamMemberServicesResponse services,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(
                types = {"string", "null"},
                format = "uuid",
                description = "The linked account; null for no sign-in. Only for a caller with `staff.list`.")
        Optional<UUID> signInAccountId,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(
                types = {"string", "null"},
                description = "That account's email, else its mobile. Only for a caller with `staff.list`.")
        Optional<String> signInLabel,

        @ArraySchema(
                arraySchema = @Schema(description = "Their week: each of the seven weekdays, Monday first."),
                minItems = 7,
                maxItems = 7)
        List<TeamWorkdayResponse> weekly,

        @Schema(description = "Its version, which a save or removal is made from.", minimum = "0")
        long version) {

    public static TeamMemberResponse from(MemberView view) {
        return new TeamMemberResponse(
                view.id(),
                view.name(),
                view.title(),
                view.highlights(),
                view.bio(),
                view.shownOnline(),
                view.photoId(),
                TeamPhotoUrls.of(view.photoId()),
                new TeamMemberServicesResponse(view.categoryIds(), view.serviceIds()),
                view.signInVisible() ? Optional.ofNullable(view.signInAccountId()) : null,
                view.signInVisible() ? Optional.ofNullable(view.signInLabel()) : null,
                view.weekly().stream().map(TeamWorkdayResponse::from).toList(),
                view.version());
    }
}
