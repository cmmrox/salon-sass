package com.salonplatform.staff.dto;

import com.salonplatform.staff.domain.LocalTimes;
import com.salonplatform.staff.service.MemberTimeOffService.BlockView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** Part of one date when a person cannot be booked. */
@Schema(
        name = "TeamMemberBlockedTime",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"id", "date", "starts", "finishes", "label", "bookingsToResolve"})
public record TeamBlockedTimeResponse(
        @Schema(format = "uuid") UUID id,

        @Schema(format = "date", description = "The salon's local date.")
        String date,

        @Schema(pattern = "^([01][0-9]|2[0-3]):(00|15|30|45)$", description = "When it starts, local `HH:MM`.")
        String starts,

        @Schema(
                pattern = "^([01][0-9]|2[0-3]):(00|15|30|45)$",
                description = "When it finishes, local `HH:MM`, after `starts`.")
        String finishes,

        @Schema(minLength = 2, maxLength = 40) String label,

        @Schema(minimum = "0", description = "How many bookings lie in it. They are kept.")
        int bookingsToResolve) {

    public static TeamBlockedTimeResponse from(BlockView view) {
        return new TeamBlockedTimeResponse(
                view.id(),
                view.date().toString(),
                LocalTimes.text(view.starts()),
                LocalTimes.text(view.finishes()),
                view.label(),
                view.bookingsToResolve());
    }
}
