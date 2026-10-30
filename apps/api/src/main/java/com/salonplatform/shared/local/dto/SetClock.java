package com.salonplatform.shared.local.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record SetClock(@NotNull Instant instant) {}
