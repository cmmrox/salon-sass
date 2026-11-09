package com.salonplatform.booking.controller;

import com.salonplatform.booking.dto.BookingDetailResponse;
import com.salonplatform.booking.dto.BookingPageResponse;
import com.salonplatform.booking.dto.BookingResponse;
import com.salonplatform.booking.dto.CancelBookingRequest;
import com.salonplatform.booking.dto.PlaceBookingRequest;
import com.salonplatform.booking.service.BookingChangeService;
import com.salonplatform.booking.service.BookingReadService;
import com.salonplatform.booking.service.BookingReadService.ListQuery;
import com.salonplatform.booking.service.HoldService;
import com.salonplatform.shared.web.ApiPaths;
import com.salonplatform.shared.web.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Holds and bookings in the workspace (F07-S01): placing a hold, reading bookings, releasing a hold and cancelling a
 * booking; the other changes of state are {@link BookingActionsController}'s. The permission
 * checks are in the services: {@code booking.manage} places and changes, {@code booking.attend} marks arrived and in
 * service, {@code calendar.read} reads.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/bookings")
@Tag(name = "booking")
class BookingsController {

    static final String PROBLEM = "#/components/schemas/Problem";

    private final HoldService holds;
    private final BookingReadService reads;
    private final BookingChangeService changes;

    BookingsController(HoldService holds, BookingReadService reads, BookingChangeService changes) {
        this.holds = holds;
        this.reads = reads;
        this.changes = changes;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "placeBookingHold",
            summary = "Holds a professional's time for a service, for the booking rules' hold length."
                    + " `booking.manage`.",
            description = "Nobody else can take the time until the hold is confirmed, released or runs out. Without"
                    + " `memberId` (\"No preference\") the server assigns, at that moment, an eligible professional free"
                    + " for the whole booking by the salon's strategy, trying the next when the time was just taken. The"
                    + " booking keeps the service's terms version, variant, segments and price and the current"
                    + " booking rules version. A repeat with the same `Idempotency-Key` and body answers the same"
                    + " booking and places nothing new.")
    @Parameter(
            name = "Idempotency-Key",
            in = ParameterIn.HEADER,
            required = true,
            description = "A UUID the client makes for this request and sends again with any retry of it.",
            schema = @Schema(type = "string", format = "uuid"))
    @ApiResponse(responseCode = "201", description = "The hold, in state `held`.")
    @ApiResponse(
            responseCode = "400",
            description = "A field is missing or wrong (the name, the mobile number, the channel, the variant, the date"
                    + " or the time), the key is not a UUID, or the body is not the documented shape: `bad_request`,"
                    + " with `errors` naming each. Nothing is stored.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
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
            description = "The professional, the service or the variant is not the salon's (removed, deleted or"
                    + " unknown), the hold this key placed is no longer shown, or the hostname names no salon:"
                    + " `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description = "Another live hold or booking of the professional overlaps it, or with \"No preference\""
                    + " nobody eligible is free then: `slot_taken`. The time cannot"
                    + " be booked (the professional does not perform the service, not a five-minute step, past, more"
                    + " than a year ahead, outside the opening hours or the professional's working time; with \"No"
                    + " preference\", nobody on the team performs the service or works then):"
                    + " `slot_unavailable`, the `detail` saying which. The salon has never saved its booking rules:"
                    + " `rules_not_set`. Nothing is stored.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "422",
            description = "The key was already used with a different body: `request_rejected`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<BookingResponse> place(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody PlaceBookingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(BookingResponse.from(holds.place(request.input(), idempotencyKey)));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "listBookings",
            summary = "The salon's bookings on local dates, by start time. `calendar.read`.",
            description = "A Professional who sees only their own bookings gets the bookings of the team member linked"
                    + " to their account, and none when no member is. Holds that ended or ran out are never listed.")
    @ApiResponse(responseCode = "200", description = "A page of bookings.")
    @ApiResponse(
            responseCode = "400",
            description = "`from` or `to` is missing or not `YYYY-MM-DD`, the range is longer than 31 days or ends"
                    + " before it starts, a `state` is not a booking state, or `memberId`, `q`, `page` or `size` is"
                    + " wrong: `bad_request`, with `errors` naming each.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Without `calendar.read`: `permission_denied`. A suspended salon: `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "The hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<BookingPageResponse> list(
            @Parameter(description = "The first local date, `YYYY-MM-DD`.", required = true)
                    @RequestParam(required = false)
                    String from,
            @Parameter(description = "The last local date, at most 30 days after `from`.", required = true)
                    @RequestParam(required = false)
                    String to,
            @Parameter(description = "Only these states; repeat it for several. All by default.")
                    @RequestParam(name = "state", required = false)
                    List<String> state,
            @Parameter(description = "Only this professional's.") @RequestParam(required = false) String memberId,
            @Parameter(
                            description = "Part of the customer's name, their mobile number in any common form, or"
                                    + " the reference, with or without its hyphen.")
                    @RequestParam(required = false)
                    String q,
            @Parameter(description = "From 0.") @RequestParam(required = false) String page,
            @Parameter(description = "1 to 100; 50 by default.") @RequestParam(required = false) String size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(BookingPageResponse.from(reads.page(new ListQuery(from, to, state, memberId, q, page, size))));
    }

    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "getBooking",
            summary = "One booking, its history and what the caller may do with it now. `calendar.read`.")
    @ApiResponse(responseCode = "200", description = "The booking.")
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Without `calendar.read`: `permission_denied`. A suspended salon: `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "No such booking of the salon, one the caller does not see (a Professional's colleague's, a"
                    + " hold that ended or ran out), or the hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<BookingDetailResponse> get(@PathVariable String id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(BookingDetailResponse.from(reads.detail(idOf(id))));
    }

    @PostMapping(path = "/{id}/release")
    @Operation(
            operationId = "releaseBookingHold",
            summary = "Gives up a hold at once, freeing its time. `booking.manage`.")
    @ApiResponse(responseCode = "204", description = "Released.")
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
            description = "No such booking of the salon, a hold already released or run out, or the hostname names no"
                    + " salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description = "It is not a hold: `invalid_state`, the `detail` naming the rule.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<Void> release(@PathVariable String id) {
        changes.release(idOf(id));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping(
            path = "/{id}/cancel",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "cancelBooking",
            summary = "Cancels a confirmed booking before it starts, with a reason. `booking.manage`.")
    @ApiResponse(responseCode = "200", description = "The booking, now `cancelled`.")
    @ApiResponse(
            responseCode = "400",
            description = "The reason is missing or not one of the four, the note is longer than 200 characters, or"
                    + " the body is not the documented shape: `bad_request`, with `errors` naming each.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
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
            description = "No such booking of the salon, a hold that ended or ran out, or the hostname names no salon:"
                    + " `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description = "Not confirmed, or its start time has come: `invalid_state`, the `detail` naming the rule.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<BookingResponse> cancel(@PathVariable String id, @RequestBody CancelBookingRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(BookingResponse.from(changes.cancel(idOf(id), request.input())));
    }

    /** A path id that is not a UUID names nothing: 404, exactly as an id that names no booking. */
    static UUID idOf(String id) {
        try {
            UUID parsed = UUID.fromString(id);
            if (parsed.toString().equalsIgnoreCase(id)) {
                return parsed;
            }
        } catch (IllegalArgumentException ex) {
            // Not found, below.
        }
        throw new NotFoundException();
    }
}
