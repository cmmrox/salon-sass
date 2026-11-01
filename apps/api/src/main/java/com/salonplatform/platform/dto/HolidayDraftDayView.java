package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/** One day of a year's draft, and what publishing the draft does to it. */
@Schema(requiredProperties = {"date", "name", "kind", "change"})
public record HolidayDraftDayView(
        LocalDate date,
        String name,

        @Schema(allowableValues = {"poya", "public", "bank", "mercantile"})
        String kind,

        @Schema(
                allowableValues = {"added", "changed", "unchanged"},
                description = "Against the published year: a day salons do not have yet, one whose name or"
                        + " kind differs from what they have, or one they have as it is.")
        String change) {}
