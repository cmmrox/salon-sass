package com.salonplatform.branding.dto;

import com.salonplatform.branding.service.BrandDraftView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/** The salon's brand draft, which only its settings staff see until one of them publishes. */
@Schema(
        name = "SalonBrandDraft",
        requiredProperties = {"version", "brand", "hasUnpublishedChanges", "publishedAt", "publishedBy"})
public record SalonBrandDraftResponse(
        @Schema(
                minimum = "0",
                description = "The draft's version; the next change must send it, an older one is"
                        + " refused with `conflict`.")
        long version,

        @Schema(requiredMode = RequiredMode.REQUIRED) SalonBrandResponse brand,

        @Schema(description = "Whether the draft differs from the published brand.")
        boolean hasUnpublishedChanges,

        @Schema(
                types = {"string", "null"},
                format = "date-time",
                description = "When the brand was last published from a draft; null when it never was.")
        Instant publishedAt,

        @Schema(
                types = {"string", "null"},
                format = "uuid",
                description = "The staff account that last published it; null when it never was.")
        UUID publishedBy) {

    public static SalonBrandDraftResponse from(BrandDraftView view) {
        return new SalonBrandDraftResponse(
                view.version(),
                SalonBrandResponse.from(view.brand()),
                view.hasUnpublishedChanges(),
                view.publishedAt(),
                view.publishedBy());
    }
}
