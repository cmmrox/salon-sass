package com.salonplatform.booking.service.impl;

import com.salonplatform.booking.domain.BookingState;
import com.salonplatform.booking.entity.Booking;
import com.salonplatform.booking.service.BookingReadService.BookingView;
import java.time.ZoneId;

/** A booking as a caller sees it: the hold's expiry only while it is a hold. */
final class BookingViews {

    private BookingViews() {}

    static BookingView of(Booking booking, ZoneId zone) {
        BookingState state = booking.state();
        return new BookingView(
                booking.getId(),
                booking.getReference(),
                state,
                booking.getStartsAt(),
                booking.getEndsAt(),
                state == BookingState.HELD ? booking.getHoldExpiresAt() : null,
                booking.getMemberId(),
                booking.getMemberName(),
                booking.getServiceId(),
                booking.getServiceName(),
                booking.getVariantId(),
                booking.getVariantName(),
                booking.getCustomerName(),
                booking.getCustomerMobile(),
                booking.channel(),
                booking.getPriceMinor(),
                booking.getCurrency(),
                booking.getCreatedAt(),
                zone);
    }
}
