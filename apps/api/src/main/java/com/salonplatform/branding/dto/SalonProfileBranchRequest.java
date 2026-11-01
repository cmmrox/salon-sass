package com.salonplatform.branding.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;

/** One branch's contact details as sent. */
@Schema(
        name = "SalonProfileBranchInput",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"id"})
public record SalonProfileBranchRequest(
        @Schema(format = "uuid", description = "One of the salon's branches.")
        String id,

        @Schema(
                types = {"boolean", "null"},
                description = "Ignored: which branch is the default is not changed here. Accepted so that a"
                        + " profile the API answered can be sent back as it is.")
        Boolean isDefault,

        @Schema(
                types = {"string", "null"},
                description = "Up to 200 characters.")
        String address,

        @Schema(
                types = {"object", "null"},
                implementation = SalonProfileLocationRequest.class,
                description = "Both coordinates, or null for none.")
        @Valid
        SalonProfileLocationRequest location,

        @Schema(
                types = {"string", "null"},
                description = "A phone number of the configured region in any common form, such as"
                        + " `011 250 4417` or `+94112504417`.")
        String phone,

        @Schema(
                types = {"string", "null"},
                description =
                        "A mobile number of the configured region in any common form, such as" + " `077 412 3300`.")
        String whatsapp) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
