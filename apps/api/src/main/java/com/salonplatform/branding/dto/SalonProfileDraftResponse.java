package com.salonplatform.branding.dto;

import com.salonplatform.branding.service.ProfileDraftView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/** The salon's profile draft, which only its settings staff see until one of them publishes. */
@Schema(
        name = "SalonProfileDraft",
        requiredProperties = {"version", "profile", "hasUnpublishedChanges", "publishedAt", "publishedBy"})
public record SalonProfileDraftResponse(
        @Schema(
                minimum = "0",
                description = "The draft's version. The next save, publication or discard must send it; an older"
                        + " one is refused with `conflict`.")
        long version,

        @Schema(requiredMode = RequiredMode.REQUIRED) SalonProfileResponse profile,

        @Schema(
                description = "Whether the draft holds something customers do not see yet: a difference from"
                        + " the published profile, or, before the first publication, anything set at all.")
        boolean hasUnpublishedChanges,

        @Schema(
                types = {"string", "null"},
                format = "date-time",
                description = "When the profile was last published; null before the first publication.")
        Instant publishedAt,

        @Schema(
                types = {"string", "null"},
                format = "uuid",
                description = "The staff account that last published it; null before the first publication.")
        UUID publishedBy) {

    public static SalonProfileDraftResponse from(ProfileDraftView view) {
        return new SalonProfileDraftResponse(
                view.version(),
                SalonProfileResponse.from(view.profile()),
                view.hasUnpublishedChanges(),
                view.publishedAt(),
                view.publishedBy());
    }
}
