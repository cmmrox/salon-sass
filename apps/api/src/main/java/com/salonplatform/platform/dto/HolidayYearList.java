package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Every year the calendar has been started for, newest first. Not paginated: the operator can keep years 2000
 * to 2100 only, so the list is never longer than 101.
 */
@Schema(requiredProperties = {"items"})
public record HolidayYearList(List<HolidayYearSummary> items) {}
