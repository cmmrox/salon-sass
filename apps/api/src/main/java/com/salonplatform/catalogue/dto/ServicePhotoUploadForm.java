package com.salonplatform.catalogue.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The multipart form of a service photo upload, as the contract describes it. The controller reads its one field
 * itself, so that a missing one is a field error naming it.
 */
@Schema(
        name = "CatalogueServicePhotoUpload",
        requiredProperties = {"file"})
public record ServicePhotoUploadForm(
        @Schema(type = "string", format = "binary", description = "The image file: PNG, JPEG or WebP, up to 5 MB.")
        String file) {}
