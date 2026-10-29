package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"staffSeats"})
public record Usage(long staffSeats) {}
