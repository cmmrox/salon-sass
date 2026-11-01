package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** One year of the holiday calendar, as the console lists it. */
@Schema(requiredProperties = {"year", "state", "revision", "dayCount", "publishedAt"})
public record HolidayYearSummary(
        int year,

        @Schema(
                allowableValues = {"draft", "published", "changed"},
                description = "`draft`: never published. `published`: salons see the draft as it is."
                        + " `changed`: published, with draft changes not yet published.")
        String state,

        @Schema(description = "How many times the year has been published; 0 before the first publication.")
        int revision,

        @Schema(description = "How many days the draft has.")
        int dayCount,

        @Schema(
                types = {"string", "null"},
                format = "date-time",
                description = "The latest publication.")
        Instant publishedAt) {}
