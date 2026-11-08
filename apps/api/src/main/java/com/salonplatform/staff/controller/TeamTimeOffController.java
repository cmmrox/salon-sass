package com.salonplatform.staff.controller;

import com.salonplatform.shared.web.ApiPaths;
import com.salonplatform.staff.dto.AddMemberBlockedTimeRequest;
import com.salonplatform.staff.dto.AddMemberLeaveRequest;
import com.salonplatform.staff.dto.AddedTeamMemberBlockedTimeResponse;
import com.salonplatform.staff.dto.AddedTeamMemberLeaveResponse;
import com.salonplatform.staff.dto.TeamPeriodImpactResponse;
import com.salonplatform.staff.service.MemberTimeOffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A team member's leave and blocked time in the workspace (STF-03, STF-05, decisions 099 and 104). Leave needs
 * {@code team.manage}, and blocked time {@code team.block}; the checks are in the service. Both are read with the schedule.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/team/members/{id}/schedule")
@Tag(name = "staff")
class TeamTimeOffController {

    private static final String PROBLEM = "#/components/schemas/Problem";

    private final MemberTimeOffService timeOff;

    TeamTimeOffController(MemberTimeOffService timeOff) {
        this.timeOff = timeOff;
    }

    @PostMapping(
            path = "/leave",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "addTeamMemberLeave",
            summary = "Adds leave for a person, as whole days. `team.manage`.",
            description = "Customers can't book them on those days. Bookings in the period are kept and listed, and are"
                    + " flagged for as long as they lie in it. Nothing is cancelled.")
    @ApiResponse(responseCode = "201", description = "The leave.")
    @ApiResponse(
            responseCode = "400",
            description =
                    "A day is not today to a year ahead by the salon's date, the last is before the first, the type"
                            + " or the note is wrong, or the body is not the documented shape: `bad_request`, with `errors` naming"
                            + " each field. Nothing is saved.",
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
            description = "It overlaps this person's leave: `conflict`. They have 100 leaves that have not ended:"
                    + " `limit_reached`. Nothing is saved.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<AddedTeamMemberLeaveResponse> addLeave(
            @PathVariable String id, @RequestBody AddMemberLeaveRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(AddedTeamMemberLeaveResponse.from(timeOff.addLeave(PathIds.of(id), request.input())));
    }

    @DeleteMapping("/leave/{leaveId}")
    @Operation(
            operationId = "removeTeamMemberLeave",
            summary = "Removes leave that has not ended, including leave under way. `team.manage`.",
            description = "They become bookable on those days again, and bookings in it are no longer flagged.")
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
            description =
                    "No such person, no such leave of theirs, leave that has ended, or the hostname names no salon:"
                            + " `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<Void> removeLeave(@PathVariable String id, @PathVariable String leaveId) {
        timeOff.removeLeave(PathIds.of(id), PathIds.of(leaveId));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping(path = "/leave/affected", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "getTeamMemberLeaveImpact",
            summary = "The bookings leave for these days would affect, before it is added. `team.manage`.")
    @ApiResponse(responseCode = "200", description = "The bookings, which would be kept; empty when there are none.")
    @ApiResponse(
            responseCode = "400",
            description =
                    "A day is missing, not `YYYY-MM-DD`, not today to a year ahead, or the last is before the first:"
                            + " `bad_request`, with `errors` naming each.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Without `team.manage`: `permission_denied`. A suspended salon: `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "No such person on the hostname's salon's team, or the hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<TeamPeriodImpactResponse> leaveAffected(
            @PathVariable String id,
            @Parameter(schema = @Schema(type = "string", format = "date"), required = true)
                    @RequestParam(required = false)
                    String firstDay,
            @Parameter(schema = @Schema(type = "string", format = "date"), required = true)
                    @RequestParam(required = false)
                    String lastDay) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(TeamPeriodImpactResponse.from(timeOff.leaveAffected(PathIds.of(id), firstDay, lastDay)));
    }

    @PostMapping(
            path = "/blocked-time",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "addTeamMemberBlockedTime",
            summary = "Blocks part of one date for a person, with a label. `team.block`.",
            description =
                    "It is stored as entered, and may lie outside their hours or on their leave. Bookings in it are"
                            + " kept and listed, and are flagged for as long as they lie in it. Nothing is cancelled.")
    @ApiResponse(responseCode = "201", description = "The block.")
    @ApiResponse(
            responseCode = "400",
            description =
                    "The date is not today to a year ahead by the salon's date, a time is not on a quarter hour, the"
                            + " finish is not after the start, the label is not 2 to 40 characters, or the body is not the"
                            + " documented shape: `bad_request`, with `errors` naming each field. Nothing is saved.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Without `team.block`: `permission_denied`. A support session: `support_read_only`. A"
                    + " read-only salon: `salon_read_only`. A suspended salon: `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "No such person on the hostname's salon's team, or the hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description = "It overlaps another of this person's blocks: `conflict`. They have 200 blocks from today on:"
                    + " `limit_reached`. Nothing is saved.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<AddedTeamMemberBlockedTimeResponse> addBlock(
            @PathVariable String id, @RequestBody AddMemberBlockedTimeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(AddedTeamMemberBlockedTimeResponse.from(timeOff.addBlock(PathIds.of(id), request.input())));
    }

    @DeleteMapping("/blocked-time/{blockId}")
    @Operation(
            operationId = "removeTeamMemberBlockedTime",
            summary = "Removes a block from today on. `team.block`.",
            description = "They become bookable in that time again, and bookings in it are no longer flagged.")
    @ApiResponse(responseCode = "204", description = "Removed.")
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Without `team.block`: `permission_denied`. A support session: `support_read_only`. A"
                    + " read-only salon: `salon_read_only`. A suspended salon: `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "No such person, no such block of theirs, one whose date has passed, or the hostname names no"
                    + " salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<Void> removeBlock(@PathVariable String id, @PathVariable String blockId) {
        timeOff.removeBlock(PathIds.of(id), PathIds.of(blockId));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping(path = "/blocked-time/affected", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "getTeamMemberBlockedTimeImpact",
            summary = "The bookings a block of this time would affect, before it is added. `team.block`.")
    @ApiResponse(responseCode = "200", description = "The bookings, which would be kept; empty when there are none.")
    @ApiResponse(
            responseCode = "400",
            description = "The date or a time is missing or wrong, or the finish is not after the start: `bad_request`,"
                    + " with `errors` naming each.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Without `team.block`: `permission_denied`. A suspended salon: `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "No such person on the hostname's salon's team, or the hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<TeamPeriodImpactResponse> blockAffected(
            @PathVariable String id,
            @Parameter(schema = @Schema(type = "string", format = "date"), required = true)
                    @RequestParam(required = false)
                    String date,
            @Parameter(schema = @Schema(type = "string"), required = true) @RequestParam(required = false)
                    String starts,
            @Parameter(schema = @Schema(type = "string"), required = true) @RequestParam(required = false)
                    String finishes) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(TeamPeriodImpactResponse.from(timeOff.blockAffected(PathIds.of(id), date, starts, finishes)));
    }
}
