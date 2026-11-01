package com.salonplatform.catalogue.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.salonplatform.catalogue.service.SalonServices.ServiceInput;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** A new service. */
@Schema(
        name = "NewCatalogueService",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"name", "categoryId", "bufferMinutes"})
public record ServiceRequest(
        @Schema(description = "2 to 40 characters after trimming, unique in the salon regardless of case.")
        String name,

        @Schema(format = "uuid", description = "One of the salon's categories.")
        UUID categoryId,

        @Schema(
                types = {"string", "null"},
                description = "Up to 90 characters; empty or null for none.")
        String description,

        @Schema(
                types = {"integer", "null"},
                format = "int64",
                description = "Minor units of the salon's currency, a whole number of units: Rs. 100 to Rs. 500,000"
                        + " (10000 to 50000000), scaled for other currencies. Required without variants; absent or"
                        + " null with them.")
        Long priceMinor,

        @Schema(
                types = {"integer", "null"},
                description = "5 to 480 minutes, in steps of 5. Required without variants; absent or null with them.")
        Integer durationMinutes,

        @Schema(description = "0 to 60 minutes after the service, in steps of 5.")
        Integer bufferMinutes,

        @Schema(description = "Whether customers can book it online; on when absent.")
        Boolean bookableOnline,

        @Schema(
                types = {"object", "null"},
                implementation = VariantsRequest.class,
                description = "The service's one set of variants (2 to 10), or absent or null for none. With variants"
                        + " the service has no price or time of its own.")
        VariantsRequest variants,

        @Schema(
                implementation = ServiceDepositRequest.class,
                description = "The service's deposit; the salon's booking rule when absent.")
        ServiceDepositRequest deposit) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }

    public ServiceInput input() {
        return new ServiceInput(
                name,
                categoryId,
                description,
                priceMinor,
                durationMinutes,
                bufferMinutes,
                bookableOnline,
                variants == null ? null : variants.input(),
                deposit == null ? null : deposit.input());
    }
}
