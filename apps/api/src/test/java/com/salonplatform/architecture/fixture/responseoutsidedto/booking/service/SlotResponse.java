package com.salonplatform.architecture.fixture.responseoutsidedto.booking.service;

import io.swagger.v3.oas.annotations.media.Schema;

/** A response record written beside the use cases instead of in dto. */
@Schema(name = "Slot")
public record SlotResponse(String startsAt) {}
