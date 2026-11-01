package com.salonplatform.branding.dto;

import com.salonplatform.branding.service.PublishedBrand;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * The salon's public brand and published business profile. Nothing in it identifies the salon beyond what its
 * customers already see.
 */
@Schema(
        name = "Salon",
        description = "The public brand and published profile of the salon the request's hostname names.")
public record SalonResponse(
        @Schema(
                description = "The salon's slug: its subdomain on the platform domain.",
                example = "alpha",
                requiredMode = RequiredMode.REQUIRED)
        String slug,

        @Schema(
                description = "The salon's published display name.",
                example = "Alpha Studio",
                requiredMode = RequiredMode.REQUIRED)
        String name,

        @Schema(
                description = "The salon's brand colour, `#RRGGBB`.",
                example = "#D6086A",
                pattern = "^#[0-9A-F]{6}$",
                requiredMode = RequiredMode.REQUIRED)
        String brandColour,

        @Schema(
                description = "The salon's time zone, an IANA name. The salon's times are shown in it.",
                example = "Asia/Colombo",
                requiredMode = RequiredMode.REQUIRED)
        String timeZone,

        @Schema(
                types = {"string", "null"},
                maxLength = 80,
                description = "The salon's published tagline; null when it has none.",
                requiredMode = RequiredMode.REQUIRED)
        String tagline,

        @Schema(
                types = {"object", "null"},
                implementation = SalonBrandImageResponse.class,
                requiredMode = RequiredMode.REQUIRED,
                description = "The published logo, served at `/api/v1/salon/brand/images/{id}`; null for none.")
        SalonBrandImageResponse logo,

        @Schema(
                types = {"object", "null"},
                implementation = SalonBrandImageResponse.class,
                requiredMode = RequiredMode.REQUIRED,
                description = "The published cover, served at `/api/v1/salon/brand/images/{id}`; null for none.")
        SalonBrandImageResponse cover,

        @Schema(
                types = {"object", "null"},
                implementation = SalonProfileResponse.class,
                requiredMode = RequiredMode.REQUIRED,
                description = "The salon's published business profile, exactly as last published; null before"
                        + " the first publication. A draft is never served here.")
        SalonProfileResponse profile) {

    public static SalonResponse from(PublishedBrand brand) {
        return new SalonResponse(
                brand.slug(),
                brand.name(),
                brand.brandColour(),
                brand.timeZone(),
                brand.tagline().orElse(null),
                brand.logo().map(SalonBrandImageResponse::from).orElse(null),
                brand.cover().map(SalonBrandImageResponse::from).orElse(null),
                brand.profile().map(SalonProfileResponse::from).orElse(null));
    }
}
