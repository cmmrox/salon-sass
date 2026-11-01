package com.salonplatform.branding.dto;

import com.salonplatform.branding.domain.BranchContact;
import com.salonplatform.branding.service.ProfileView;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.Comparator;
import java.util.List;

/** A salon's business profile: the draft its settings staff keep, or the published copy everyone is served. */
@Schema(
        name = "SalonProfile",
        description = "A salon's business profile. Every field is optional: null when it is not set.",
        requiredProperties = {"description", "links", "branches"})
public record SalonProfileResponse(
        @Schema(
                types = {"string", "null"},
                maxLength = 500,
                description = "The salon's description.",
                example = "A friendly neighbourhood salon for hair, beauty and bridal.")
        String description,

        @Schema(requiredMode = RequiredMode.REQUIRED) SalonProfileLinksResponse links,

        @ArraySchema(
                schema = @Schema(implementation = SalonProfileBranchResponse.class),
                arraySchema =
                        @Schema(
                                description =
                                        "Each branch's contact details, the default branch first. In R1 this holds"
                                                + " exactly the default branch; a client must not assume it holds one."))
        List<SalonProfileBranchResponse> branches) {

    public static SalonProfileResponse from(ProfileView view) {
        var content = view.content();
        return new SalonProfileResponse(
                content.description(),
                new SalonProfileLinksResponse(content.instagram(), content.facebook(), content.website()),
                content.branches().stream()
                        .sorted(Comparator.comparing(
                                (BranchContact b) -> !b.branchId().equals(view.defaultBranchId())))
                        .map(b ->
                                SalonProfileBranchResponse.from(b, b.branchId().equals(view.defaultBranchId())))
                        .toList());
    }
}
