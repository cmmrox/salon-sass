package com.salonplatform.catalogue.dto;

import com.salonplatform.catalogue.service.SalonServices.ServiceView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** One of the salon's services, with its current terms. */
@Schema(
        name = "CatalogueService",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {
            "id",
            "name",
            "categoryId",
            "description",
            "bookableOnline",
            "photoId",
            "photoUrl",
            "version",
            "terms"
        })
public record ServiceResponse(
        @Schema(format = "uuid") UUID id,
        @Schema(minLength = 2, maxLength = 40) String name,
        @Schema(format = "uuid") UUID categoryId,

        @Schema(
                types = {"string", "null"},
                maxLength = 90,
                description = "Null when it has none.")
        String description,

        boolean bookableOnline,

        @Schema(
                types = {"string", "null"},
                format = "uuid",
                description = "Its photo; null when it has none.")
        UUID photoId,

        @Schema(
                types = {"string", "null"},
                description = "Where its photo is served on the salon's hostname; null when it has none.")
        String photoUrl,

        @Schema(description = "Its version, which a change is made from.", minimum = "0")
        long version,

        ServiceTermsResponse terms) {

    public static ServiceResponse from(ServiceView view) {
        return new ServiceResponse(
                view.id(),
                view.name(),
                view.categoryId(),
                view.description(),
                view.bookableOnline(),
                view.photoId(),
                PhotoUrls.of(view.photoId()),
                view.version(),
                ServiceTermsResponse.from(view.terms()));
    }
}
