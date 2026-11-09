package com.salonplatform.booking;

import com.salonplatform.shared.outbox.EventType;
import java.util.UUID;

/**
 * A booking of the event's salon was confirmed: by staff confirming its hold, or by its payment (F08). Published
 * through the outbox in the confirming transaction (CR-021), so it exists only once the confirmation committed;
 * {@code notification} (F11) schedules the customer's messages when it consumes it. It carries ids only; the salon is
 * the event's own, not a field.
 *
 * @param bookingId the booking
 * @param memberId the professional it is with
 * @param serviceId the service booked
 */
@EventType("booking.BookingConfirmed.v1")
public record BookingConfirmed(UUID bookingId, UUID memberId, UUID serviceId) {}
