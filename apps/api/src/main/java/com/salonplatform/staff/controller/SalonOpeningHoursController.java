package com.salonplatform.staff.controller;

import com.salonplatform.shared.web.ApiPaths;
import com.salonplatform.staff.dto.OpeningHoursSettingsResponse;
import com.salonplatform.staff.dto.OpeningWeekdayRequest;
import com.salonplatform.staff.dto.SalonOpeningHoursResponse;
import com.salonplatform.staff.dto.SaveOpeningHoursRequest;
import com.salonplatform.staff.service.OpeningHoursService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The salon's opening hours (F04-S03): public, and as its settings staff keep them. On the salon's hostname
 * only; the salon is the hostname's. Permissions are checked by the service.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/salon/opening-hours", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "staff")
class SalonOpeningHoursController {

    private static final String PROBLEM = "#/components/schemas/Problem";

    private final OpeningHoursService hours;

    SalonOpeningHoursController(OpeningHoursService hours) {
        this.hours = hours;
    }

    @GetMapping
    @Operation(
            operationId = "getSalonOpeningHours",
            summary = "The salon's opening information: its weekly hours and the coming exceptions. Public.",
            description = "The exceptions run from the salon's today to 180 days ahead in its time zone: closed"
                    + " days (special closures and holidays the salon closes) and holidays with special hours."
                    + " A closure's reason is never included.")
    @ApiResponse(responseCode = "200", description = "The opening information.")
    @ApiResponse(
            responseCode = "403",
            description = "A suspended salon: `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "The hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<SalonOpeningHoursResponse> publicHours() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(SalonOpeningHoursResponse.from(hours.publicHours()));
    }

    @GetMapping("/settings")
    @Operation(
            operationId = "getSalonOpeningHoursSettings",
            summary = "The weekly hours as settings staff keep them, with the bookings they leave outside."
                    + " `settings.read`.")
    @ApiResponse(responseCode = "200", description = "The hours.")
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
    ResponseEntity<OpeningHoursSettingsResponse> settings() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(OpeningHoursSettingsResponse.from(hours.settings()));
    }

    @PutMapping(path = "/settings", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "saveSalonOpeningHours",
            summary = "Saves the weekly hours; they apply at once. `settings.manage`.",
            description = "Each of the seven weekdays once: open with an opening and a closing time on a quarter"
                    + " hour (`HH:MM`), closing after opening, or closed with no times. Bookings the new hours leave"
                    + " outside are kept and listed.")
    @ApiResponse(responseCode = "200", description = "The hours as saved.")
    @ApiResponse(
            responseCode = "400",
            description = "A field broke a rule, or the body is not the documented shape: `bad_request`, with"
                    + " `errors` naming each field (`weekly[0].closes`). Nothing is saved.",
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
            description = "The hours changed since `version`: `conflict`. Nothing is saved.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<OpeningHoursSettingsResponse> save(@Valid @RequestBody SaveOpeningHoursRequest request) {
        List<OpeningHoursService.DayInput> week = request.weekly() == null
                ? null
                : request.weekly().stream()
                        .map(SalonOpeningHoursController::input)
                        .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(OpeningHoursSettingsResponse.from(hours.save(request.version(), week)));
    }

    private static OpeningHoursService.DayInput input(OpeningWeekdayRequest day) {
        return day == null ? null : new OpeningHoursService.DayInput(day.day(), day.open(), day.opens(), day.closes());
    }
}
