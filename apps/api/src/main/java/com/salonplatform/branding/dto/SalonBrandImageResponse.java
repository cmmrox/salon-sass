package com.salonplatform.branding.dto;

import com.salonplatform.branding.service.BrandImageView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** A brand image as the brand names it: served at `/api/v1/salon/brand/images/{id}`. */
@Schema(
        name = "SalonBrandImage",
        requiredProperties = {"id", "width", "height"})
public record SalonBrandImageResponse(
        @Schema(format = "uuid") UUID id,

        @Schema(minimum = "1", maximum = "1600", description = "Its width in pixels, as kept.")
        int width,

        @Schema(minimum = "1", maximum = "1600", description = "Its height in pixels, as kept.")
        int height) {

    static SalonBrandImageResponse from(BrandImageView view) {
        return new SalonBrandImageResponse(view.id(), view.width(), view.height());
    }
}
