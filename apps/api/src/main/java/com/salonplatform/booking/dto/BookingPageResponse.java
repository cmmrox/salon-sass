package com.salonplatform.booking.dto;

import com.salonplatform.booking.service.BookingReadService.BookingPage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** A page of bookings, by start time. */
@Schema(
        name = "BookingPage",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"items", "page", "size", "total"})
public record BookingPageResponse(
        List<BookingResponse> items,
        @Schema(minimum = "0") int page,
        @Schema(minimum = "1", maximum = "100") int size,
        @Schema(minimum = "0") long total) {

    public static BookingPageResponse from(BookingPage page) {
        return new BookingPageResponse(
                page.items().stream().map(BookingResponse::from).toList(), page.page(), page.size(), page.total());
    }
}
