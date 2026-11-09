package com.salonplatform.staff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.salonplatform.staff.domain.LocalTimes;
import com.salonplatform.staff.service.MemberScheduleService.ChangeView;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A change to a person's hours for one date. The note exists only for a caller with {@code team.manage} (decision 102(g)):
 * a null {@link Optional} leaves the field out, and an empty one writes {@code null} for a change with no note.
 */
@Schema(
        name = "TeamMemberDateChange",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"id", "date", "working", "starts", "finishes", "breaks"})
public record TeamDateChangeResponse(
        @Schema(format = "uuid") UUID id,

        @Schema(format = "date", description = "The salon's local date.")
        String date,

        boolean working,

        @Schema(
                types = {"string", "null"},
                pattern = "^([01][0-9]|2[0-3]):(00|15|30|45)$",
                description = "When they start, local `HH:MM`; null when not working.")
        String starts,

        @Schema(
                types = {"string", "null"},
                pattern = "^([01][0-9]|2[0-3]):(00|15|30|45)$",
                description = "When they finish, local `HH:MM`, after `starts`; null when not working.")
        String finishes,

        @ArraySchema(arraySchema = @Schema(description = "In time order; empty when not working."), maxItems = 5)
        List<TeamBreakResponse> breaks,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(
                types = {"string", "null"},
                maxLength = 60,
                description = "The note; null for none. Only for a caller with `team.manage`.")
        Optional<String> note) {

    public static TeamDateChangeResponse from(ChangeView view) {
        return new TeamDateChangeResponse(
                view.id(),
                view.date().toString(),
                view.starts() != null,
                LocalTimes.text(view.starts()),
                LocalTimes.text(view.finishes()),
                view.breaks().stream().map(TeamBreakResponse::from).toList(),
                view.noteVisible() ? Optional.ofNullable(view.note()) : null);
    }
}
