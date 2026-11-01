package com.salonplatform.branding.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * A profile as sent for the draft: the same shape the API answers. Text is trimmed and an empty value
 * means "not set"; the API's rules and their field errors are in the operation's description.
 */
@Schema(
        name = "SalonProfileInput",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"links", "branches"})
public record SalonProfileRequest(
        @Schema(
                types = {"string", "null"},
                description = "Up to 500 characters.")
        String description,

        @NotNull @Valid SalonProfileLinksRequest links,

        @ArraySchema(
                schema = @Schema(implementation = SalonProfileBranchRequest.class),
                arraySchema =
                        @Schema(
                                description = "The branches whose details are set; a branch not listed keeps"
                                        + " its draft. A branch id that is not the salon's is a field error."))
        @NotNull
        List<@NotNull @Valid SalonProfileBranchRequest> branches) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused("profile." + field);
    }
}
