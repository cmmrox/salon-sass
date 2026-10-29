package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(requiredProperties = {"items", "page", "size", "total"})
public record InvoicePage(List<InvoiceSummary> items, int page, int size, long total) {}
