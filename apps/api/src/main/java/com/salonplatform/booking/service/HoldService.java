package com.salonplatform.booking.service;

import com.salonplatform.booking.service.BookingReadService.BookingView;

/**
 * Placing a hold on a professional's time for a service, with {@code booking.manage} (F07-S01 AC-1 to AC-4, AC-10): it
 * lasts 10 minutes, keeps the terms it was made on, and gets a reference. The database refuses one that overlaps
 * another live hold or booking of the same professional.
 */
public interface HoldService {

    /** A hold as a request asks for it; nothing here has been checked. */
    record HoldInput(
            String memberId,
            String serviceId,
            String variantId,
            String date,
            String time,
            String customerName,
            String customerMobile,
            String channel) {}

    /**
     * Places the hold, or answers the one an earlier request with the same key placed.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 400 naming each field; 403; 404; 409
     *     {@code slot_unavailable}, {@code slot_taken} or {@code rules_not_set}; 422 {@code request_rejected} for a key
     *     used with another request
     */
    BookingView place(HoldInput input, String idempotencyKey);
}
