package com.salonplatform.branding.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The multipart form of a brand image upload, as the contract describes it. The controller reads its two
 * fields itself, so that a missing or wrong one is a field error naming it.
 */
@Schema(
        name = "SalonBrandImageUpload",
        requiredProperties = {"kind", "file"})
public record SalonBrandImageUploadForm(
        @Schema(allowableValues = {"logo", "cover"}) String kind,

        @Schema(type = "string", format = "binary", description = "The image file: PNG, JPEG or WebP, up to 5 MB.")
        String file) {}
