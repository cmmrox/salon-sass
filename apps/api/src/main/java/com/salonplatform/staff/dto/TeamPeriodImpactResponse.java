package com.salonplatform.staff.dto;

import com.salonplatform.staff.AffectedBooking;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** The bookings a period of leave or a block would affect. They would be kept. */
@Schema(
        name = "TeamPeriodImpact",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"affected"})
public record TeamPeriodImpactResponse(List<TeamAffectedBookingResponse> affected) {

    public static TeamPeriodImpactResponse from(List<AffectedBooking> affected) {
        return new TeamPeriodImpactResponse(
                affected.stream().map(TeamAffectedBookingResponse::from).toList());
    }
}
