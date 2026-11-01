package com.salonplatform.booking.dto;

import com.salonplatform.booking.service.BookingRulesService.HistoryEntry;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One version of the booking rules: when it was saved, by whom, and which rules it changed. */
@Schema(
        name = "BookingRulesHistoryEntry",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"versionId", "version", "createdAt", "createdBy", "createdByLabel", "changedFields"})
public record BookingRulesHistoryEntryResponse(
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
                                    "slotIntervalMinutes",
                                    "minimumNoticeMinutes",
                                    "bookingHorizonDays",
                                    "deposit",
                                    "freeCancellationHours",
                                    "lateCancellation",
                                    "noShow",
                                    "reschedule"
                                }),
                arraySchema =
                        @Schema(
                                description = "The rules it changed from the version before it; every rule for the"
                                        + " first version."))
        List<String> changedFields) {

    static BookingRulesHistoryEntryResponse from(HistoryEntry entry) {
        return new BookingRulesHistoryEntryResponse(
                entry.versionId(),
                entry.version(),
                entry.createdAt(),
                entry.createdBy(),
                entry.createdByLabel(),
                entry.changedFields());
    }
}
