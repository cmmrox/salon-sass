package com.salonplatform.booking.controller;

import com.salonplatform.booking.domain.BookingAction;
import com.salonplatform.booking.dto.BookingResponse;
import com.salonplatform.booking.service.BookingChangeService;
import com.salonplatform.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A booking's changes of state that carry no body (F07-S01 AC-5, AC-7): confirm, arrive, start, no-show and its undoing,
 * each {@code POST /api/v1/bookings/{id}/<action>}. The state machine and the permission checks are in the service.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/bookings")
@Tag(name = "booking")
class BookingActionsController {

    private static final String PROBLEM = BookingsController.PROBLEM;

    private final BookingChangeService changes;

    BookingActionsController(BookingChangeService changes) {
        this.changes = changes;
    }

    @PostMapping(path = "/{id}/confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "confirmBooking", summary = "Confirms a hold that has not run out. `booking.manage`.")
    @ApiResponse(responseCode = "200", description = "The booking, now `confirmed`.")
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Without `booking.manage`: `permission_denied`. A support session: `support_read_only`. A"
                    + " read-only salon: `salon_read_only`. A suspended salon: `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "No such booking of the salon, or the hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description =
                    "It is not a hold: `invalid_state`. The hold has run out, and its time is free for others: `hold_expired`. The `detail` names the rule.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<BookingResponse> confirm(@PathVariable String id) {
        return take(id, BookingAction.CONFIRM);
    }

    @PostMapping(path = "/{id}/arrive", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "markBookingArrived",
            summary =
                    "Marks the customer arrived, on the booking's own date. `booking.attend`; a Professional on their own bookings.")
    @ApiResponse(responseCode = "200", description = "The booking, now `arrived`.")
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Without `booking.attend`: `permission_denied`. A support session: `support_read_only`. A"
                    + " read-only salon: `salon_read_only`. A suspended salon: `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description =
                    "No such booking of the salon, another professional's for a Professional, a hold that ended or ran out, or the hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description = "Not confirmed, or not the booking's date: `invalid_state`, the `detail` naming the rule.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<BookingResponse> arrive(@PathVariable String id) {
        return take(id, BookingAction.ARRIVE);
    }

    @PostMapping(path = "/{id}/start", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "startBookingService",
            summary =
                    "Starts the service of a customer who has arrived. `booking.attend`; a Professional on their own bookings.")
    @ApiResponse(responseCode = "200", description = "The booking, now `in_service`.")
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Without `booking.attend`: `permission_denied`. A support session: `support_read_only`. A"
                    + " read-only salon: `salon_read_only`. A suspended salon: `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description =
                    "No such booking of the salon, another professional's for a Professional, a hold that ended or ran out, or the hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description = "The customer has not arrived: `invalid_state`, the `detail` naming the rule.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<BookingResponse> start(@PathVariable String id) {
        return take(id, BookingAction.START);
    }

    @PostMapping(path = "/{id}/no-show", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "markBookingNoShow",
            summary = "Marks a confirmed booking a no-show, at or after its start time. `booking.manage`.")
    @ApiResponse(responseCode = "200", description = "The booking, now `no_show`.")
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Without `booking.manage`: `permission_denied`. A support session: `support_read_only`. A"
                    + " read-only salon: `salon_read_only`. A suspended salon: `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description =
                    "No such booking of the salon, a hold that ended or ran out, or the hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description =
                    "Not confirmed, or its start time has not come: `invalid_state`, the `detail` naming the rule.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<BookingResponse> noShow(@PathVariable String id) {
        return take(id, BookingAction.NO_SHOW);
    }

    @PostMapping(path = "/{id}/undo-no-show", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "undoBookingNoShow",
            summary = "Undoes a no-show on the booking's own date. `booking.manage`.")
    @ApiResponse(responseCode = "200", description = "The booking, `confirmed` again.")
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Without `booking.manage`: `permission_denied`. A support session: `support_read_only`. A"
                    + " read-only salon: `salon_read_only`. A suspended salon: `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "No such booking of the salon, or the hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description =
                    "Not a no-show, not the booking's date, or the professional no longer performs the service: `invalid_state`, the `detail` naming the rule. The time has been booked since: `slot_taken`, and it stays a no-show.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<BookingResponse> undoNoShow(@PathVariable String id) {
        return take(id, BookingAction.UNDO_NO_SHOW);
    }

    private ResponseEntity<BookingResponse> take(String id, BookingAction action) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(BookingResponse.from(changes.change(BookingsController.idOf(id), action)));
    }
}
