package com.salonplatform.booking.dto;

import com.salonplatform.booking.domain.BookingAction;
import com.salonplatform.booking.service.BookingReadService.BookingDetail;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** One booking, its history oldest first, and what the caller may do with it now. */
@Schema(
        name = "BookingDetail",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"booking", "history", "allowedActions"})
public record BookingDetailResponse(
        BookingResponse booking,
        List<BookingHistoryEntryResponse> history,

        @ArraySchema(
                schema =
                        @Schema(
                                allowableValues = {
                                    "confirm",
                                    "release",
                                    "arrive",
                                    "start",
                                    "cancel",
                                    "no-show",
                                    "undo-no-show"
                                }),
                arraySchema =
                        @Schema(
                                description = "The actions the caller's role allows on it now, by the booking's"
                                        + " rules and the time."))
        List<String> allowedActions) {

    public static BookingDetailResponse from(BookingDetail detail) {
        return new BookingDetailResponse(
                BookingResponse.from(detail.booking()),
                detail.history().stream()
                        .map(entry -> BookingHistoryEntryResponse.from(
                                entry, detail.booking().zone()))
                        .toList(),
                detail.allowedActions().stream().map(BookingAction::apiName).toList());
    }
}
