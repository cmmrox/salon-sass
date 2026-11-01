package com.salonplatform.staff.controller;

import com.salonplatform.shared.web.ApiPaths;
import com.salonplatform.staff.dto.AddClosureRequest;
import com.salonplatform.staff.dto.ClosureImpactResponse;
import com.salonplatform.staff.dto.SalonClosureResponse;
import com.salonplatform.staff.service.ClosureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
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
 * The salon's special closures (F04-S03): a whole day closed, with the bookings it affects listed and kept.
 * On the salon's hostname only. Permissions are checked by the service.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/salon/closures", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "staff")
class SalonClosuresController {

    private static final String PROBLEM = "#/components/schemas/Problem";

    private final ClosureService closures;

    SalonClosuresController(ClosureService closures) {
        this.closures = closures;
    }

    @GetMapping
    @Operation(
            operationId = "listSalonClosures",
            summary = "The closures from the salon's today on, in date order. `settings.read`.",
            description = "Not paginated: a closure is at most a year ahead, so the list is short.")
    @ApiResponse(responseCode = "200", description = "The closures.")
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Without `settings.read`: `permission_denied`. A suspended salon: `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "The hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<List<SalonClosureResponse>> upcoming() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(closures.upcoming().stream()
                        .map(SalonClosureResponse::from)
                        .toList());
    }

    @GetMapping("/impact")
    @Operation(
            operationId = "getSalonClosureImpact",
            summary = "The bookings on a date that closing it would affect. `settings.read`.")
    @ApiResponse(responseCode = "200", description = "The bookings; empty when there are none.")
    @ApiResponse(
            responseCode = "400",
            description = "The date is missing, not `YYYY-MM-DD`, past or more than a year ahead: `bad_request`,"
                    + " with `errors` naming `date`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Without `settings.read`: `permission_denied`. A suspended salon: `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "The hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<ClosureImpactResponse> impact(
            @Parameter(schema = @Schema(type = "string", format = "date"), required = true)
                    @RequestParam(required = false)
                    String date) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ClosureImpactResponse.from(closures.impact(date)));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "addSalonClosure",
            summary = "Closes the salon all day on a date; it applies at once. `settings.manage`.",
            description = "The date is the salon's local date, today to a year ahead. Bookings on it are kept and"
                    + " listed. The reason is for the team only and is never public.")
    @ApiResponse(responseCode = "201", description = "The closure.")
    @ApiResponse(
            responseCode = "400",
            description = "The date is not today to a year ahead, the reason is over 60 characters, or the body is"
                    + " not the documented shape: `bad_request`, with `errors` naming `date` or `reason`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Without `settings.manage`: `permission_denied`. A support session: `support_read_only`."
                    + " A read-only salon: `salon_read_only`. A suspended salon: `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "The hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description = "The date is closed already: `conflict`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<SalonClosureResponse> close(@RequestBody AddClosureRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(SalonClosureResponse.from(closures.close(request.date(), request.reason())));
    }

    @DeleteMapping("/{id}")
    @Operation(
            operationId = "reopenSalonClosure",
            summary = "Reopens a closed date that has not passed. `settings.manage`.")
    @ApiResponse(responseCode = "204", description = "Reopened.")
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Without `settings.manage`: `permission_denied`. A support session: `support_read_only`."
                    + " A read-only salon: `salon_read_only`. A suspended salon: `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "No such closure of this salon (another salon's answers the same): `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description = "The date has passed: `invalid_state`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<Void> reopen(@PathVariable String id) {
        closures.reopen(id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
