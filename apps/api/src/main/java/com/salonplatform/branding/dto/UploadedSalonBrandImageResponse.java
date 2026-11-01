package com.salonplatform.branding.dto;

import com.salonplatform.branding.service.BrandImageView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** A brand image just uploaded: kept, but used by nothing until a draft is saved with its id. */
@Schema(
        name = "UploadedSalonBrandImage",
        requiredProperties = {"id", "kind", "width", "height"})
public record UploadedSalonBrandImageResponse(
        @Schema(format = "uuid") UUID id,
        @Schema(allowableValues = {"logo", "cover"}) String kind,
        @Schema(minimum = "1", maximum = "1600") int width,
        @Schema(minimum = "1", maximum = "1600") int height) {

    public static UploadedSalonBrandImageResponse from(BrandImageView view) {
        return new UploadedSalonBrandImageResponse(view.id(), view.kind(), view.width(), view.height());
    }
}
