package com.salonplatform.staff.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.salonplatform.staff.service.MemberScheduleService.BreakInput;
import com.salonplatform.staff.service.MemberScheduleService.DayInput;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** A save of a person's whole week, made from the version the caller read. */
@Schema(
        name = "SaveTeamMemberWeek",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"version", "weekly"})
public record SaveMemberWeekRequest(
        @Schema(minimum = "0", description = "The schedule's version as read; 0 when it has never been saved.")
        Long version,

        @Schema(description = "Each of the seven weekdays once.")
        List<TeamWorkdayRequest> weekly) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }

    public List<DayInput> input() {
        return weekly == null
                ? null
                : weekly.stream()
                        .map(day -> day == null
                                ? null
                                : new DayInput(
                                        day.day(), day.working(), day.starts(), day.finishes(), breaks(day.breaks())))
                        .toList();
    }

    static List<BreakInput> breaks(List<TeamBreakRequest> breaks) {
        return breaks == null
                ? null
                : breaks.stream()
                        .map(each -> each == null ? null : new BreakInput(each.starts(), each.finishes()))
                        .toList();
    }
}
