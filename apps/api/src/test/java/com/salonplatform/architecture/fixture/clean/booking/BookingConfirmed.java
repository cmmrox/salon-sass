package com.salonplatform.architecture.fixture.clean.booking;

import com.salonplatform.shared.outbox.EventType;
import java.util.UUID;

@EventType("booking.BookingConfirmed.v1")
public record BookingConfirmed(UUID bookingId) {}
