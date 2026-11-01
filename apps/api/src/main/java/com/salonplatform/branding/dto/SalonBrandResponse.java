package com.salonplatform.branding.dto;

import com.salonplatform.branding.service.BrandView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/** A salon's brand, as its draft holds it. */
@Schema(
        name = "SalonBrand",
        requiredProperties = {"name", "tagline", "colour", "logo", "cover"})
public record SalonBrandResponse(
        @Schema(maxLength = 120, example = "Alpha Studio") String name,

        @Schema(
                types = {"string", "null"},
                maxLength = 80,
                example = "Simple booking. Better salon days.")
        String tagline,

        @Schema(pattern = "^#[0-9A-F]{6}$", example = "#D6086A")
        String colour,

        @Schema(
                types = {"object", "null"},
                implementation = SalonBrandImageResponse.class,
                requiredMode = RequiredMode.REQUIRED,
                description = "The logo, a square PNG of at most 512 x 512, or null for none (the salon's initial).")
        SalonBrandImageResponse logo,

        @Schema(
                types = {"object", "null"},
                implementation = SalonBrandImageResponse.class,
                requiredMode = RequiredMode.REQUIRED,
                description = "The cover, a JPEG at most 1600 wide, or null for none (the neutral cover).")
        SalonBrandImageResponse cover) {

    static SalonBrandResponse from(BrandView view) {
        return new SalonBrandResponse(
                view.name(),
                view.tagline().orElse(null),
                view.colour(),
                view.logo().map(SalonBrandImageResponse::from).orElse(null),
                view.cover().map(SalonBrandImageResponse::from).orElse(null));
    }
}
