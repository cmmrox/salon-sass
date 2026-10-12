package com.salonplatform.branding.api;

import com.salonplatform.branding.application.PublishedBrand;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/** The salon's public brand. Nothing in it identifies the salon beyond what its customers already see. */
@Schema(name = "Salon", description = "The public brand of the salon the request's hostname names.")
public record SalonResponse(
        @Schema(description = "The salon's slug: its subdomain on the platform domain.", example = "alpha",
                requiredMode = RequiredMode.REQUIRED)
        String slug,
        @Schema(description = "The salon's published display name.", example = "Alpha Studio",
                requiredMode = RequiredMode.REQUIRED)
        String name,
        @Schema(description = "The salon's brand colour, `#RRGGBB`.", example = "#D6086A", pattern = "^#[0-9A-F]{6}$",
                requiredMode = RequiredMode.REQUIRED)
        String brandColour) {

    static SalonResponse from(PublishedBrand brand) {
        return new SalonResponse(brand.slug(), brand.name(), brand.brandColour());
    }
}
