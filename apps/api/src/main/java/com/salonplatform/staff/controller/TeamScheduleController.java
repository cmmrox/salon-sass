package com.salonplatform.staff.controller;

import com.salonplatform.shared.web.ApiPaths;
import com.salonplatform.staff.dto.AddMemberDateChangeRequest;
import com.salonplatform.staff.dto.AddedTeamMemberDateChangeResponse;
import com.salonplatform.staff.dto.SaveMemberWeekRequest;
import com.salonplatform.staff.dto.SavedTeamMemberWeekResponse;
import com.salonplatform.staff.dto.TeamMemberScheduleResponse;
import com.salonplatform.staff.service.MemberScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A team member's schedule in the workspace (STF-02, decision 102): their weekly hours with breaks, and their changes for
 * single dates. Reading needs {@code team.read}, and every change {@code team.manage}; the checks are in the service.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/team/members/{id}/schedule")
@Tag(name = "staff")
class TeamScheduleController {

    private static final String PROBLEM = "#/components/schemas/Problem";

    private final MemberScheduleService schedules;

    TeamScheduleController(MemberScheduleService schedules) {
        this.schedules = schedules;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "getTeamMemberSchedule",
            summary =
                    "A person's weekly hours, and their date changes, leave and blocked time from today on. `team.read`.",
            description = "A date change's `note`, and a leave's `type` and `note`, are present only for a caller with"
                    + " `team.manage`.")
    @ApiResponse(responseCode = "200", description = "The schedule.")
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Without `team.read`: `permission_denied`. A suspended salon: `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "No such person on the hostname's salon's team, or the hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<TeamMemberScheduleResponse> read(@PathVariable String id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(TeamMemberScheduleResponse.from(schedules.read(PathIds.of(id))));
    }

    @PutMapping(
            path = "/weekly",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "saveTeamMemberWeek",
            summary = "Saves a person's whole week. `team.manage`.",
            description = "Each of the seven weekdays once. Date changes are left alone. It may go beyond the salon's"
                    + " opening hours, and bookings still follow those. Bookings the new hours leave outside are kept"
                    + " and listed.")
    @ApiResponse(responseCode = "200", description = "The week as saved.")
    @ApiResponse(
            responseCode = "400",
            description =
                    "A broken rule, or the body is not the documented shape: `bad_request`, with `errors` naming"
                            + " each field (`weekly[0].finishes`, `weekly[0].breaks[1]`, `weekly[0].breaks`). Nothing is saved.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Without `team.manage`: `permission_denied`. A support session: `support_read_only`. A"
                    + " read-only salon: `salon_read_only`. A suspended salon: `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "No such person on the hostname's salon's team, or the hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description = "`version` is not the schedule's: `conflict`. Nothing changes: `invalid_state`. Nothing is"
                    + " saved.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<SavedTeamMemberWeekResponse> saveWeek(
            @PathVariable String id, @RequestBody SaveMemberWeekRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(SavedTeamMemberWeekResponse.from(
                        schedules.saveWeek(PathIds.of(id), request.version(), request.input())));
    }

    @PostMapping(
            path = "/date-changes",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "addTeamMemberDateChange",
            summary = "Changes a person's hours for one date. `team.manage`.",
            description = "It replaces the weekly hours on that date only, both the times and the breaks. Bookings it"
                    + " leaves outside are kept and listed.")
    @ApiResponse(responseCode = "201", description = "The change.")
    @ApiResponse(
            responseCode = "400",
            description = "The date is not today to a year ahead by the salon's date, another broken rule, or the body"
                    + " is not the documented shape: `bad_request`, with `errors` naming each field. Nothing is saved.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Without `team.manage`: `permission_denied`. A support session: `support_read_only`. A"
                    + " read-only salon: `salon_read_only`. A suspended salon: `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "No such person on the hostname's salon's team, or the hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description = "The date already has a change: `conflict`. The person has 100 upcoming changes:"
                    + " `limit_reached`. Nothing is saved.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<AddedTeamMemberDateChangeResponse> addDateChange(
            @PathVariable String id, @RequestBody AddMemberDateChangeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(AddedTeamMemberDateChangeResponse.from(schedules.addDateChange(PathIds.of(id), request.input())));
    }

    @DeleteMapping("/date-changes/{changeId}")
    @Operation(
            operationId = "removeTeamMemberDateChange",
            summary = "Removes a date change that has not passed: the date is back to the weekly hours. `team.manage`.")
    @ApiResponse(responseCode = "204", description = "Removed.")
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Without `team.manage`: `permission_denied`. A support session: `support_read_only`. A"
                    + " read-only salon: `salon_read_only`. A suspended salon: `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "No such person, no such change of theirs, one that has passed, or the hostname names no"
                    + " salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<Void> removeDateChange(@PathVariable String id, @PathVariable String changeId) {
        schedules.removeDateChange(PathIds.of(id), PathIds.of(changeId));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
