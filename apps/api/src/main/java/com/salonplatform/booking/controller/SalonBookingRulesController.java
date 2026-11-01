package com.salonplatform.booking.controller;

import com.salonplatform.booking.dto.BookingRulesHistoryResponse;
import com.salonplatform.booking.dto.BookingRulesSettingsResponse;
import com.salonplatform.booking.dto.NoBookingRules;
import com.salonplatform.booking.dto.PublicBookingRulesResponse;
import com.salonplatform.booking.dto.SaveBookingRulesRequest;
import com.salonplatform.booking.service.BookingRulesService;
import com.salonplatform.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.NullNode;

/**
 * The salon's booking rules (F04-S04): public, and as its settings staff keep them, with their history. On the
 * salon's hostname only; the salon is the hostname's. Permissions are checked by the service.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/salon/booking-rules", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "booking")
class SalonBookingRulesController {

    private static final String PROBLEM = "#/components/schemas/Problem";

    private final BookingRulesService rules;

    SalonBookingRulesController(BookingRulesService rules) {
        this.rules = rules;
    }

    @GetMapping
    @Operation(
            operationId = "getSalonBookingRules",
            summary = "The salon's current booking rules, as customers see them before paying. Public.",
            description = "JSON `null` before the salon first saves its rules. Amounts are minor units of"
                    + " `currency`. A booking made now keeps `versionId`.")
    @ApiResponse(
            responseCode = "200",
            description = "The current rules, or `null`.",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema =
                                    @Schema(
                                            oneOf = {PublicBookingRulesResponse.class, NoBookingRules.class},
                                            description = "The current rules; null before the first save.")))
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
    ResponseEntity<Object> publicRules() {
        Object body = rules.publicRules()
                .<Object>map(PublicBookingRulesResponse::from)
                .orElse(NullNode.getInstance());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping("/settings")
    @Operation(
            operationId = "getSalonBookingRulesSettings",
            summary = "The booking rules as settings staff keep them. `settings.read`.")
    @ApiResponse(responseCode = "200", description = "The rules.")
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
    ResponseEntity<BookingRulesSettingsResponse> settings() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(BookingRulesSettingsResponse.from(rules.settings()));
    }

    @PutMapping(path = "/settings", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "saveSalonBookingRules",
            summary = "Saves the booking rules as a new version, current at once for new bookings."
                    + " `settings.manage`.",
            description = "Every rule, each one of the salon's choices. Existing bookings keep the version they were"
                    + " made under; earlier versions are never changed.")
    @ApiResponse(responseCode = "200", description = "The rules as saved, with the new version's number.")
    @ApiResponse(
            responseCode = "400",
            description = "A field broke a rule, or the body is not the documented shape: `bad_request`, with"
                    + " `errors` naming each field (`rules.deposit.capMinor`). Nothing is saved.",
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
            description = "`version` is not the current version: `conflict`. The rules are the current version's"
                    + " already: `invalid_state`. Nothing is saved.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<BookingRulesSettingsResponse> save(@Valid @RequestBody SaveBookingRulesRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(BookingRulesSettingsResponse.from(rules.save(
                        request.version(),
                        request.rules() == null ? null : request.rules().input())));
    }

    @GetMapping("/history")
    @Operation(
            operationId = "getSalonBookingRulesHistory",
            summary = "The booking rules' versions, newest first, paginated. `settings.read`.")
    @ApiResponse(responseCode = "200", description = "A page of versions.")
    @ApiResponse(
            responseCode = "400",
            description = "`page` below 0 or above 100000, or `size` outside 1 to 100: `bad_request`.",
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
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<BookingRulesHistoryResponse> history(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(BookingRulesHistoryResponse.from(rules.history(page, size)));
    }
}
