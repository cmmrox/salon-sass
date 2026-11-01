package com.salonplatform.platform.controller;

import com.salonplatform.platform.dto.HolidayCalendarImportForm;
import com.salonplatform.platform.dto.HolidayDayChangeRequest;
import com.salonplatform.platform.dto.HolidayDayRequest;
import com.salonplatform.platform.dto.HolidayPublicationRequest;
import com.salonplatform.platform.dto.HolidayYearDetail;
import com.salonplatform.platform.dto.HolidayYearList;
import com.salonplatform.platform.service.HolidayCalendarService;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.shared.web.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The holiday calendar in the console (F04-S03): years, a year's draft, CSV import, single-day edits and
 * publication. Console host only (OperatorHostFilter answers 404 on any other host, a salon's included);
 * Admins change it, Support operators read it. A year outside 2000 to 2100, or a path date outside its year,
 * is not found.
 */
@RestController
@Tag(name = "platform")
@RequestMapping(path = "/api/v1/operator/holiday-calendars", produces = MediaType.APPLICATION_JSON_VALUE)
class OperatorHolidayCalendarController {

    private static final String PROBLEM = "#/components/schemas/Problem";

    private final HolidayCalendarService calendar;

    OperatorHolidayCalendarController(HolidayCalendarService calendar) {
        this.calendar = calendar;
    }

    private static int yearOf(String year) {
        if (year == null || !year.matches("\\d{4}")) {
            throw new NotFoundException();
        }
        return Integer.parseInt(year);
    }

    private static <T> ResponseEntity<T> answer(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping
    @Operation(operationId = "listHolidayCalendars", summary = "Every year started, newest first. Admin and Support.")
    @ApiResponse(responseCode = "200", description = "The years.")
    @ApiResponse(
            responseCode = "401",
            description = "No valid operator session: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Without the permission: `permission_denied`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<HolidayYearList> years() {
        return answer(calendar.years());
    }

    @GetMapping("/{year}")
    @Operation(
            operationId = "getHolidayCalendar",
            summary = "A year's draft against what salons have, and what a publication would add, change and"
                    + " withdraw. Admin and Support.")
    @ApiResponse(responseCode = "200", description = "The year.")
    @ApiResponse(
            responseCode = "401",
            description = "No valid operator session: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Without the permission: `permission_denied`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "The year has not been started, or is outside 2000 to 2100: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<HolidayYearDetail> year(@PathVariable String year) {
        return answer(calendar.year(yearOf(year)));
    }

    @PostMapping(path = "/{year}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            operationId = "importHolidayCalendar",
            summary = "Replaces the year's draft with a CSV file's days, starting the year when needed. Admin.",
            description = "UTF-8, up to 64 KB and 400 days: one day per line as `date,name,kind`, the date"
                    + " `YYYY-MM-DD` inside the year, the name 1 to 120 characters, the kind `poya`, `public`, `bank`"
                    + " or `mercantile`. An optional first line names the columns; a field holding a comma is"
                    + " quoted. All or nothing: any bad line refuses the whole file.")
    @ApiResponse(responseCode = "200", description = "The year, its draft replaced.")
    @ApiResponse(
            responseCode = "400",
            description = "The file is missing, or has bad lines: `bad_request`, with one error per bad line"
                    + " (`field` `file`, its `line` and the reason as `detail`). Nothing changes.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "401",
            description = "No valid operator session: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Not an Admin: `permission_denied`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "The year is outside 2000 to 2100: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content =
                    @Content(
                            mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                            schema = @Schema(implementation = HolidayCalendarImportForm.class)))
    ResponseEntity<HolidayYearDetail> importFile(
            @PathVariable String year,
            @Parameter(hidden = true) @RequestPart(value = "file", required = false) MultipartFile file)
            throws IOException {
        int which = yearOf(year);
        if (file == null) {
            throw new ApplicationProblemException(
                    400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("file", "is required")));
        }
        return answer(calendar.importFile(which, file.getBytes()));
    }

    @PostMapping(path = "/{year}/days", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "addHolidayCalendarDay",
            summary = "Adds a day to the year's draft, starting the year when needed. Admin.")
    @ApiResponse(responseCode = "200", description = "The year, with the day.")
    @ApiResponse(
            responseCode = "400",
            description = "A field broke a rule: `bad_request`, with `errors` naming `date`, `name` or `kind`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "401",
            description = "No valid operator session: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Not an Admin: `permission_denied`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "The year is outside 2000 to 2100: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description = "The draft has that date already: `conflict`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<HolidayYearDetail> addDay(@PathVariable String year, @RequestBody HolidayDayRequest request) {
        return answer(calendar.addDay(
                yearOf(year), new HolidayCalendarService.DayInput(request.date(), request.name(), request.kind())));
    }

    @PutMapping(path = "/{year}/days/{date}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "changeHolidayCalendarDay", summary = "Changes a draft day's name and kind. Admin.")
    @ApiResponse(responseCode = "200", description = "The year, with the day changed.")
    @ApiResponse(
            responseCode = "400",
            description = "A field broke a rule: `bad_request`, with `errors` naming `name` or `kind`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "401",
            description = "No valid operator session: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Not an Admin: `permission_denied`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "No such year, or the draft has no such date: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<HolidayYearDetail> changeDay(
            @PathVariable String year, @PathVariable String date, @RequestBody HolidayDayChangeRequest request) {
        return answer(calendar.changeDay(
                yearOf(year), date, new HolidayCalendarService.DayInput(date, request.name(), request.kind())));
    }

    @DeleteMapping("/{year}/days/{date}")
    @Operation(operationId = "removeHolidayCalendarDay", summary = "Removes a day from the year's draft. Admin.")
    @ApiResponse(responseCode = "200", description = "The year, without the day.")
    @ApiResponse(
            responseCode = "401",
            description = "No valid operator session: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Not an Admin: `permission_denied`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "No such year, or the draft has no such date: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<HolidayYearDetail> removeDay(@PathVariable String year, @PathVariable String date) {
        return answer(calendar.removeDay(yearOf(year), date));
    }

    @PostMapping(path = "/{year}/publication", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "publishHolidayCalendar",
            summary = "Publishes the year's draft to every salon as its next revision. Admin.",
            description = "Salons see the days the revision adds or changes as \"To review\"; their choices for"
                    + " unchanged days still apply, and a withdrawn day's choice no longer does.")
    @ApiResponse(responseCode = "200", description = "The year, published.")
    @ApiResponse(
            responseCode = "400",
            description = "The body is not the documented shape: `bad_request`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "401",
            description = "No valid operator session: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Not an Admin: `permission_denied`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "The year has not been started: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description = "The year changed since `version`: `conflict`. The draft adds, changes and withdraws"
                    + " nothing: `invalid_state`. Nothing is published.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<HolidayYearDetail> publish(
            @PathVariable String year, @RequestBody HolidayPublicationRequest request) {
        if (request.version() == null || request.version() < 0) {
            throw new ApplicationProblemException(
                    400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("version", "must be zero or more")));
        }
        return answer(calendar.publish(yearOf(year), request.version()));
    }
}
