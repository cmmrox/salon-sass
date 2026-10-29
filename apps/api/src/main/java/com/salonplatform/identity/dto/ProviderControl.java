package com.salonplatform.identity.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record ProviderControl(
        @Min(0) @Max(50) int failures, @Min(0) @Max(60000) long delayMs) {}
