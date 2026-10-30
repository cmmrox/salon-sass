package com.salonplatform.architecture.fixture.openapiroot.booking;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** A provided record that is also a REST response shape. */
public record BookingSlot(@Schema(format = "date-time") Instant startsAt) {}
