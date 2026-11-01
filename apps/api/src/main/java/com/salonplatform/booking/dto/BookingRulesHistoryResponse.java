package com.salonplatform.booking.dto;

import com.salonplatform.booking.service.BookingRulesService.History;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** A page of the booking rules' versions, newest first. */
@Schema(
        name = "BookingRulesHistory",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"items", "page", "size", "total"})
public record BookingRulesHistoryResponse(
        List<BookingRulesHistoryEntryResponse> items,

        @Schema(minimum = "0", description = "The page, from 0.")
        int page,

        @Schema(minimum = "1", maximum = "100") int size,

        @Schema(minimum = "0", description = "How many versions there are.")
        long total) {

    public static BookingRulesHistoryResponse from(History history) {
        return new BookingRulesHistoryResponse(
                history.items().stream()
                        .map(BookingRulesHistoryEntryResponse::from)
                        .toList(),
                history.page(),
                history.size(),
                history.total());
    }
}
