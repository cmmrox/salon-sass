package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/** A published day the draft no longer has: publishing the draft withdraws it. */
@Schema(requiredProperties = {"date", "name", "kind"})
public record HolidayDayView(
        LocalDate date,
        String name,

        @Schema(allowableValues = {"poya", "public", "bank", "mercantile"})
        String kind) {}
