package com.salonplatform.staff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.salonplatform.staff.service.MemberTimeOffService.LeaveView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Optional;
import java.util.UUID;

/**
 * A person's leave. Its type and note exist only for a caller with {@code team.manage} (decision 104(a)): a null field is
 * left out, and for the note an empty {@link Optional} writes {@code null} for leave with no note.
 */
@Schema(
        name = "TeamMemberLeave",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"id", "firstDay", "lastDay", "bookingsToResolve"})
public record TeamLeaveResponse(
        @Schema(format = "uuid") UUID id,

        @Schema(format = "date", description = "The first day, the salon's local date.")
        String firstDay,

        @Schema(format = "date", description = "The last day, on or after `firstDay`.")
        String lastDay,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(
                allowableValues = {"annual", "sick", "personal", "other"},
                description = "Its type. Only for a caller with `team.manage`.")
        String type,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(
                types = {"string", "null"},
                maxLength = 60,
                description = "Its note; null for none. Only for a caller with `team.manage`.")
        Optional<String> note,

        @Schema(minimum = "0", description = "How many bookings lie in what is left of it. They are kept.")
        int bookingsToResolve) {

    public static TeamLeaveResponse from(LeaveView view) {
        return new TeamLeaveResponse(
                view.id(),
                view.firstDay().toString(),
                view.lastDay().toString(),
                view.privateVisible() ? view.type().value() : null,
                view.privateVisible() ? Optional.ofNullable(view.note()) : null,
                view.bookingsToResolve());
    }
}
