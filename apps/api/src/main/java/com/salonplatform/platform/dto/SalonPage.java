package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(requiredProperties = {"items", "page", "size", "total"})
public record SalonPage(List<SalonSummary> items, int page, int size, long total) {}
