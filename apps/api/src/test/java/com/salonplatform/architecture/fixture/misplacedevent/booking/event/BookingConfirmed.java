package com.salonplatform.architecture.fixture.misplacedevent.booking.event;

import com.salonplatform.shared.outbox.EventType;
import java.util.UUID;

@EventType("booking.BookingConfirmed.v1")
public record BookingConfirmed(UUID bookingId) {}
