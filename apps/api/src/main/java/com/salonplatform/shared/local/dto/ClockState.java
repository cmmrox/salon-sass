package com.salonplatform.shared.local.dto;

import java.time.Instant;

/** The clock now, and whether it has been set. */
public record ClockState(Instant now, boolean fixed) {}
