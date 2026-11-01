package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** A year of the holiday calendar as the console works on it: its draft against what salons have. */
@Schema(requiredProperties = {"year", "state", "revision", "version", "publishedAt", "days", "withdrawn", "changes"})
public record HolidayYearDetail(
        int year,

        @Schema(allowableValues = {"draft", "published", "changed"})
        String state,

        @Schema(description = "How many times the year has been published; 0 before the first publication.")
        int revision,

        @Schema(description = "Moves on with every change to the draft and every publication; publishing names it.")
        long version,

        @Schema(
                types = {"string", "null"},
                format = "date-time")
        Instant publishedAt,

        @Schema(description = "The draft's days, in date order.")
        List<HolidayDraftDayView> days,

        @Schema(description = "Published days the draft no longer has, in date order.")
        List<HolidayDayView> withdrawn,

        HolidayRevisionCounts changes) {}
