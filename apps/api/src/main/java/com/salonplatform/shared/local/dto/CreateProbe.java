package com.salonplatform.shared.local.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** What the probe's provider is told to do. Every field is optional. */
public record CreateProbe(
        @Min(0) @Max(50) Integer failures,
        @Min(0) @Max(60000) Long delayMs,
        Boolean abort) {}
