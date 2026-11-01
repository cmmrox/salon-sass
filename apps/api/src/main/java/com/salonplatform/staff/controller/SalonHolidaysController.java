package com.salonplatform.staff.controller;

import com.salonplatform.shared.web.ApiPaths;
import com.salonplatform.shared.web.NotFoundException;
import com.salonplatform.staff.dto.AcceptHolidaysRequest;
import com.salonplatform.staff.dto.HolidayChoiceRequest;
import com.salonplatform.staff.dto.HolidayDayChoiceRequest;
import com.salonplatform.staff.dto.HolidayYearResponse;
import com.salonplatform.staff.dto.SalonHolidaysResponse;
import com.salonplatform.staff.service.HolidayChoiceService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The salon's decisions about the operator's published holidays (F04-S03). On the salon's hostname only.
 * Permissions are checked by the service.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/salon/holidays", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "staff")
class SalonHolidaysController {

    private static final String PROBLEM = "#/components/schemas/Problem";

    private final HolidayChoiceService holidays;

    SalonHolidaysController(HolidayChoiceService holidays) {
        this.holidays = holidays;
    }

    private static int yearOf(String year) {
        if (year == null || !year.matches("\\d{4}")) {
            throw new NotFoundException();
        }
        return Integer.parseInt(year);
    }

    private static ResponseEntity<HolidayYearResponse> answer(HolidayChoiceService.HolidayYear year) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(HolidayYearResponse.from(year));
    }

    @GetMapping
    @Operation(
            operationId = "getSalonHolidays",
            summary = "The published holiday years from the salon's current year on, with its choices."
                    + " `settings.read`.")
    @ApiResponse(responseCode = "200", description = "The years; empty when the operator has published none.")
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
    ResponseEntity<SalonHolidaysResponse> years() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(SalonHolidaysResponse.from(holidays.years()));
    }

    @PostMapping(path = "/{year}/acceptance", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "acceptSalonHolidays",
            summary = "Accepts the salon's choices for a published year; they apply at once. `settings.manage`.",
            description = "A choice for every day still to come that is to review, and optionally for other days"
                    + " still to come: `closed`, `open` (as usual) or `special` with times on a quarter hour.")
    @ApiResponse(responseCode = "200", description = "The year, reviewed.")
    @ApiResponse(
            responseCode = "400",
            description = "A choice broke a rule, a day to review has no choice, or the body is not the documented"
                    + " shape: `bad_request`, with `errors` naming each field (`choices[0].closes`). Nothing is saved.",
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
            description = "The year is not published, or the hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description = "The review changed since `version`: `conflict`. Nothing is saved.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<HolidayYearResponse> accept(
            @PathVariable String year, @Valid @RequestBody AcceptHolidaysRequest request) {
        List<HolidayChoiceService.ChoiceInput> choices = request.choices() == null
                ? null
                : request.choices().stream().map(SalonHolidaysController::input).toList();
        return answer(holidays.accept(yearOf(year), request.version(), choices));
    }

    @PutMapping(path = "/{year}/days/{date}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "chooseSalonHoliday",
            summary = "Changes one holiday's choice after the year's acceptance; it applies at once."
                    + " `settings.manage`.")
    @ApiResponse(responseCode = "200", description = "The year, with the choice.")
    @ApiResponse(
            responseCode = "400",
            description = "The choice broke a rule: `bad_request`, with `errors` naming `choice`, `opens` or"
                    + " `closes`. Nothing is saved.",
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
            description = "The year is not published, or the date is not one of its holidays: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description = "The year has not been accepted yet, or the day has passed: `invalid_state`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<HolidayYearResponse> choose(
            @PathVariable String year, @PathVariable String date, @RequestBody HolidayDayChoiceRequest request) {
        return answer(holidays.choose(
                yearOf(year),
                date,
                new HolidayChoiceService.ChoiceInput(date, request.choice(), request.opens(), request.closes())));
    }

    private static HolidayChoiceService.ChoiceInput input(HolidayChoiceRequest choice) {
        return choice == null
                ? null
                : new HolidayChoiceService.ChoiceInput(choice.date(), choice.choice(), choice.opens(), choice.closes());
    }
}
