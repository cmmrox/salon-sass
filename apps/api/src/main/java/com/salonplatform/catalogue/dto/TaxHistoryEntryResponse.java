package com.salonplatform.catalogue.dto;

import com.salonplatform.catalogue.service.TaxSettingsService.HistoryEntry;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One version of the tax settings: when it was saved, by whom, and which settings it changed. */
@Schema(
        name = "CatalogueTaxHistoryEntry",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"versionId", "version", "createdAt", "createdBy", "createdByLabel", "changedFields"})
public record TaxHistoryEntryResponse(
        UUID versionId,

        @Schema(minimum = "1") long version,

        Instant createdAt,

        @Schema(description = "The staff account that saved it.")
        UUID createdBy,

        @Schema(
                types = {"string", "null"},
                description = "The author's email, else mobile number, as the staff list shows the account; null"
                        + " when it is not known.")
        String createdByLabel,

        @ArraySchema(
                schema =
                        @Schema(
                                allowableValues = {
                                    "vat.registered",
                                    "vat.registrationNumber",
                                    "vat.ratePercent",
                                    "vat.name",
                                    "serviceCharge.on",
                                    "serviceCharge.percent",
                                    "serviceCharge.onProducts",
                                    "serviceCharge.name"
                                }),
                arraySchema =
                        @Schema(
                                description = "The settings it changed from the version before it, every one for"
                                        + " the first version. `vat.ratePercent`, `vat.name` and"
                                        + " `serviceCharge.name` change when configuration did."))
        List<String> changedFields) {

    static TaxHistoryEntryResponse from(HistoryEntry entry) {
        return new TaxHistoryEntryResponse(
                entry.versionId(),
                entry.version(),
                entry.createdAt(),
                entry.createdBy(),
                entry.createdByLabel(),
                entry.changedFields());
    }
}
