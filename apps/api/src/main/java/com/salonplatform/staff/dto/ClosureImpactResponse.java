package com.salonplatform.staff.dto;

import com.salonplatform.staff.service.ClosureService.Impact;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/** The bookings on a date a closure would affect. */
@Schema(
        name = "ClosureImpact",
        requiredProperties = {"date", "bookings"})
public record ClosureImpactResponse(LocalDate date, List<AffectedBookingResponse> bookings) {

    public static ClosureImpactResponse from(Impact impact) {
        return new ClosureImpactResponse(
                impact.date(),
                impact.bookings().stream().map(AffectedBookingResponse::from).toList());
    }
}
