package com.salonplatform.branding.dto;

import com.salonplatform.branding.domain.BranchContact;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.UUID;

/** One branch's public contact details. */
@Schema(
        name = "SalonProfileBranch",
        requiredProperties = {"id", "isDefault", "address", "location", "phone", "whatsapp"})
public record SalonProfileBranchResponse(
        @Schema(format = "uuid", description = "The branch.")
        UUID id,

        @Schema(description = "Whether it is the salon's default branch.")
        boolean isDefault,

        @Schema(
                types = {"string", "null"},
                maxLength = 200,
                example = "No. 24, Flower Road, Colombo 07")
        String address,

        @Schema(
                types = {"object", "null"},
                implementation = SalonProfileLocationResponse.class,
                requiredMode = RequiredMode.REQUIRED,
                description = "The branch's map position, or null when it is not set.")
        SalonProfileLocationResponse location,

        @Schema(
                types = {"string", "null"},
                pattern = "^\\+[1-9][0-9]{6,14}$",
                description = "The branch's phone number in E.164; screens show it in the national format.",
                example = "+94112504417")
        String phone,

        @Schema(
                types = {"string", "null"},
                pattern = "^\\+[1-9][0-9]{6,14}$",
                description = "The salon's own WhatsApp number, a mobile number in E.164.",
                example = "+94774123300")
        String whatsapp) {

    static SalonProfileBranchResponse from(BranchContact contact, boolean isDefault) {
        return new SalonProfileBranchResponse(
                contact.branchId(),
                isDefault,
                contact.address(),
                contact.location() == null
                        ? null
                        : new SalonProfileLocationResponse(
                                contact.location().latitude().doubleValue(),
                                contact.location().longitude().doubleValue()),
                contact.phone(),
                contact.whatsapp());
    }
}
