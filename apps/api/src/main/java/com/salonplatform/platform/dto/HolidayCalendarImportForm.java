package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The multipart form of a calendar import, as the contract describes it. The controller reads the file
 * itself, so that a missing one is a field error naming it.
 */
@Schema(
        name = "HolidayCalendarImport",
        requiredProperties = {"file"})
public record HolidayCalendarImportForm(
        @Schema(
                type = "string",
                format = "binary",
                description = "A UTF-8 CSV file of up to 64 KB: one day per line as `date,name,kind`, with an"
                        + " optional first line naming those columns.")
        String file) {}
