package com.salonplatform.booking.service;

import com.salonplatform.booking.domain.BookingAction;
import com.salonplatform.booking.domain.BookingChannel;
import com.salonplatform.booking.domain.BookingState;
import com.salonplatform.booking.domain.CancellationReason;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Reading the salon's bookings with {@code calendar.read} (F07-S01 AC-11): a page of them by start time, and one with
 * its history and what the caller may do with it now. A Professional whose data scope is {@code own} reaches only the
 * bookings of the team member linked to their account; another's is not found. A hold that ended or has run out is
 * never seen.
 */
public interface BookingReadService {

    /** A list as a request asks for it; nothing here has been checked. */
    record ListQuery(
            String from, String to, List<String> states, String memberId, String q, String page, String size) {}

    /**
     * A booking as a caller sees it.
     *
     * @param zone the salon's time zone, in which its instants are shown
     * @param customerMobile in E.164
     * @param holdExpiresAt when the hold runs out, while it is a hold; else null
     */
    record BookingView(
            UUID id,
            String reference,
            BookingState state,
            Instant startsAt,
            Instant endsAt,
            Instant holdExpiresAt,
            UUID memberId,
            String memberName,
            UUID serviceId,
            String serviceName,
            UUID variantId,
            String variantName,
            String customerName,
            String customerMobile,
            BookingChannel channel,
            long priceMinor,
            String currency,
            Instant createdAt,
            ZoneId zone) {}

    /**
     * One change of its state.
     *
     * @param from null for the placing of the hold
     * @param byStaff the staff account that made it, or null when the system did
     * @param byLabel how that account is named (email, else mobile), or null when unknown or the system
     */
    record HistoryView(
            Instant at,
            BookingState from,
            BookingState to,
            UUID byStaff,
            String byLabel,
            CancellationReason reason,
            String note) {}

    /** A booking with its history, oldest first, and the actions the caller may take on it now. */
    record BookingDetail(BookingView booking, List<HistoryView> history, List<BookingAction> allowedActions) {}

    record BookingPage(List<BookingView> items, int page, int size, long total) {}

    /**
     * @throws com.salonplatform.shared.web.ApplicationProblemException 400 naming each field (a range of more than 31
     *     days included); 403 without {@code calendar.read}
     */
    BookingPage page(ListQuery query);

    /** @throws com.salonplatform.shared.web.NotFoundException for one the caller cannot reach or see */
    BookingDetail detail(UUID id);
}
