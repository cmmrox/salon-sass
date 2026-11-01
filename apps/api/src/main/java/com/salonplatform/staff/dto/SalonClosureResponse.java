package com.salonplatform.staff.dto;

import com.salonplatform.staff.service.ClosureService.ClosureView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;

/** A special closure, as the salon's team sees it. */
@Schema(
        name = "SalonClosure",
        requiredProperties = {"id", "date", "reason", "affectedBookings"})
public record SalonClosureResponse(
        UUID id,

        @Schema(description = "The salon's local date, closed all day.")
        LocalDate date,

        @Schema(
                types = {"string", "null"},
                description = "The team's note; never public.")
        String reason,

        @Schema(description = "How many bookings are on the date. They are kept.")
        int affectedBookings) {

    public static SalonClosureResponse from(ClosureView closure) {
        return new SalonClosureResponse(closure.id(), closure.date(), closure.reason(), closure.affectedBookings());
    }
}
