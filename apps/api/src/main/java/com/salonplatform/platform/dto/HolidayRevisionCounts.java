package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** What publishing the draft would do: how many days it adds, changes and withdraws. */
@Schema(requiredProperties = {"added", "changed", "withdrawn"})
public record HolidayRevisionCounts(int added, int changed, int withdrawn) {}
