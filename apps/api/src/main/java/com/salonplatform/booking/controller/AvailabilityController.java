package com.salonplatform.booking.controller;

import com.salonplatform.booking.dto.AvailabilityResponse;
import com.salonplatform.booking.dto.OpenDaysResponse;
import com.salonplatform.booking.service.AvailabilityService;
import com.salonplatform.booking.service.AvailabilityService.Times;
import com.salonplatform.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Free times and open days for the front desk (F07-S02), worked out live on every call. {@code booking.manage},
 * checked by the service.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/bookings", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "booking")
class AvailabilityController {

    private static final String PROBLEM = BookingsController.PROBLEM;

    private final AvailabilityService availability;

    AvailabilityController(AvailabilityService availability) {
        this.availability = availability;
    }

    @GetMapping("/availability")
    @Operation(
            operationId = "getBookingAvailability",
            summary = "The start times a booking of a service could take on a local date. `booking.manage`.",
            description = "Each time fits the whole booking inside the salon's opening hours and one of the"
                    + " professional's working windows, and overlaps none of their live bookings or unexpired holds,"
                    + " buffers included. Times are on the booking rules' slot interval from local midnight and after"
                    + " the current time; the customer notice and horizon do not apply to staff. Without `memberId`"
                    + " (\"No preference\") each time shows the eligible professional the salon's strategy picks"
                    + " among those free then.")
    @ApiResponse(responseCode = "200", description = "The times, or none and why.")
    @ApiResponse(
            responseCode = "400",
            description = "`serviceId` missing or not a UUID, `variantId` or `memberId` not a UUID, `date` not"
                    + " `YYYY-MM-DD` or before today or more than one year ahead, or the variant wrong for the service:"
                    + " `bad_request`, with `errors` naming each.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Without `booking.manage` (a support session included): `permission_denied`. A suspended"
                    + " salon: `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "The service, the variant or the professional is not the salon's (removed, deleted or"
                    + " unknown), or the hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description = "The professional does not perform the service: `slot_unavailable`. The salon has never"
                    + " saved its booking rules: `rules_not_set`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<AvailabilityResponse> availability(
            @Parameter(description = "The service.", required = true) @RequestParam(required = false) String serviceId,
            @Parameter(description = "One of the service's variants; required when it has them.")
                    @RequestParam(required = false)
                    String variantId,
            @Parameter(description = "The salon's local date, `YYYY-MM-DD`: today to one year ahead.", required = true)
                    @RequestParam(required = false)
                    String date,
            @Parameter(description = "The professional; leave it out for \"No preference\".")
                    @RequestParam(required = false)
                    String memberId) {
        Times times = availability.freeTimes(serviceId, variantId, date, memberId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(AvailabilityResponse.from(times.answer(), times.zone()));
    }

    @GetMapping("/open-days")
    @Operation(
            operationId = "getBookingOpenDays",
            summary = "The local dates in a range on which the salon is open. `booking.manage`.",
            description = "Weekly hours, holiday choices and closures decide, as for the opening hours.")
    @ApiResponse(responseCode = "200", description = "The open dates, in order.")
    @ApiResponse(
            responseCode = "400",
            description = "`from` or `to` missing or not `YYYY-MM-DD`, `from` before today, `to` before `from`, or"
                    + " more than 400 days: `bad_request`, with `errors` naming each.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Without `booking.manage` (a support session included): `permission_denied`. A suspended"
                    + " salon: `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "The hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<OpenDaysResponse> openDays(
            @Parameter(description = "The first local date, `YYYY-MM-DD`: today or later.", required = true)
                    @RequestParam(required = false)
                    String from,
            @Parameter(description = "The last local date, at most 399 days after `from`.", required = true)
                    @RequestParam(required = false)
                    String to) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new OpenDaysResponse(availability.openDays(from, to)));
    }
}
