package com.salonplatform.architecture.fixture.clean.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Slot")
public record SlotResponse(String startsAt) {}
