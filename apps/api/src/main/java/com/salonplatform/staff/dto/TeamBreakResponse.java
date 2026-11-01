package com.salonplatform.staff.dto;

import com.salonplatform.staff.TimeWindow;
import com.salonplatform.staff.domain.LocalTimes;
import io.swagger.v3.oas.annotations.media.Schema;

/** A break in a person's day. */
@Schema(
        name = "TeamBreak",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"starts", "finishes"})
public record TeamBreakResponse(
        @Schema(pattern = "^([01][0-9]|2[0-3]):(00|15|30|45)$", description = "When it starts, local `HH:MM`.")
        String starts,

        @Schema(pattern = "^([01][0-9]|2[0-3]):(00|15|30|45)$", description = "When it ends, local `HH:MM`.")
        String finishes) {

    static TeamBreakResponse from(TimeWindow window) {
        return new TeamBreakResponse(LocalTimes.text(window.starts()), LocalTimes.text(window.finishes()));
    }
}
