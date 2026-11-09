package com.salonplatform.staff.controller;

import com.salonplatform.shared.images.ImageUpload;
import com.salonplatform.shared.web.ApiPaths;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.staff.dto.NewTeamMemberRequest;
import com.salonplatform.staff.dto.SaveTeamMemberRequest;
import com.salonplatform.staff.dto.SignInAccountListResponse;
import com.salonplatform.staff.dto.TeamMemberListResponse;
import com.salonplatform.staff.dto.TeamMemberPhotoUploadForm;
import com.salonplatform.staff.dto.TeamMemberResponse;
import com.salonplatform.staff.service.MemberPhotos;
import com.salonplatform.staff.service.TeamMemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The salon's team in the workspace (STF-01, decision 100). Reading needs {@code team.read}, and every change
 * {@code team.manage}; the checks are in the services.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/team")
@Tag(name = "staff")
class TeamMemberController {

    private static final String PROBLEM = "#/components/schemas/Problem";

    private final TeamMemberService team;
    private final MemberPhotos photos;

    TeamMemberController(TeamMemberService team, MemberPhotos photos) {
        this.team = team;
        this.photos = photos;
    }

    @GetMapping(path = "/members", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "listTeamMembers",
            summary = "Everyone on the team, in name order. `team.read`.",
            description = "Returned whole: a salon has at most 200. `signInAccountId` and `signInLabel` are present"
                    + " only for a caller with `staff.list`.")
    @ApiResponse(responseCode = "200", description = "The team.")
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
            description = "The hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<TeamMemberListResponse> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(TeamMemberListResponse.from(team.list()));
    }

    @GetMapping(path = "/members/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getTeamMember", summary = "A person on the team. `team.read`.")
    @ApiResponse(responseCode = "200", description = "The person.")
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
            description = "No such person on the hostname's salon's team, a removed one included, or the hostname"
                    + " names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<TeamMemberResponse> get(@PathVariable String id) {
        return answer(team.get(PathIds.of(id)));
    }

    @PostMapping(
            path = "/members",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "addTeamMember",
            summary = "Adds a person to the team. `team.manage`.",
            description = "A person with no sign-in takes a staff seat; one linked to a free account does not.")
    @ApiResponse(responseCode = "201", description = "The person.")
    @ApiResponse(
            responseCode = "400",
            description = "A broken rule: `bad_request`, with `errors` naming each field. Nothing is saved.",
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
            description = "The hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description = "No free staff seat: `entitlement_exceeded`, with `errors` naming `staffSeats`. The salon"
                    + " already has 200 people: `limit_reached`. Nothing is saved.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<TeamMemberResponse> add(@RequestBody NewTeamMemberRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(TeamMemberResponse.from(team.add(request.input())));
    }

    @PutMapping(
            path = "/members/{id}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "saveTeamMember",
            summary = "Saves every field of a person. `team.manage`.",
            description = "Unlinking an account that stays takes a staff seat; linking to a free account frees one.")
    @ApiResponse(responseCode = "200", description = "The person.")
    @ApiResponse(
            responseCode = "400",
            description = "A broken rule: `bad_request`, with `errors` naming each field. Nothing is saved.",
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
            description = "`version` is not the person's: `conflict`. Nothing changes: `invalid_state`. No free staff"
                    + " seat: `entitlement_exceeded`, with `errors` naming `staffSeats`. It stops them performing a"
                    + " service with an upcoming booking: `in_use`, with one `errors` item per service, `field`"
                    + " `services` and the service's name in `detail`. Nothing is saved.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<TeamMemberResponse> save(@PathVariable String id, @RequestBody SaveTeamMemberRequest request) {
        return answer(team.save(PathIds.of(id), request.input(), request.version()));
    }

    @DeleteMapping("/members/{id}")
    @Operation(
            operationId = "removeTeamMember",
            summary = "Removes a person from the team. `team.manage`.",
            description = "They leave the list, the public team and the seat count. Their name stays for past"
                    + " bookings, and their sign-in account stays active and free to link.")
    @ApiResponse(responseCode = "204", description = "Removed.")
    @ApiResponse(
            responseCode = "400",
            description = "`version` is missing or below 0: `bad_request`, with `errors` naming it.",
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
            description = "They have upcoming bookings: `in_use`, with an `errors` item whose `field` is"
                    + " `upcomingBookings` and whose `detail` is how many. `version` is not the person's: `conflict`."
                    + " Nothing changes.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<Void> remove(
            @PathVariable String id,
            @Parameter(description = "The person's version, as read.", required = true) @RequestParam(required = false)
                    Long version) {
        team.remove(PathIds.of(id), version);
        return ResponseEntity.noContent().build();
    }

    @PutMapping(
            path = "/members/{id}/photo",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "uploadTeamMemberPhoto",
            summary = "Sets the person's photo, replacing any earlier one. `team.manage`.",
            description = "PNG, JPEG or WebP of up to 5 MB and at least 320 x 320 pixels, checked by decoding, cropped"
                    + " to its centre square and kept as a JPEG of at most 800 x 800, re-encoded, so no metadata is"
                    + " kept. The replaced photo is no longer served.")
    @ApiResponse(responseCode = "200", description = "The person with their new photo.")
    @ApiResponse(
            responseCode = "400",
            description = "A file that breaks a rule, or no file: `bad_request`, with `errors` naming `file`. The"
                    + " person keeps their previous photo.",
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
            description = "The person changed at the same moment: `conflict`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "413",
            description = "The request is over the server's limit: `request_rejected`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description =
                    "Too many photos are being decoded, or the database is not answering:" + " `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content =
                    @Content(
                            mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                            schema = @Schema(implementation = TeamMemberPhotoUploadForm.class)))
    ResponseEntity<TeamMemberResponse> uploadPhoto(
            @PathVariable String id,
            @Parameter(hidden = true) @RequestPart(value = "file", required = false) MultipartFile file)
            throws IOException {
        if (file == null) {
            throw new ApplicationProblemException(
                    400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("file", "is required")));
        }
        return answer(photos.upload(PathIds.of(id), new ImageUpload(file.getSize(), file::getBytes)));
    }

    @DeleteMapping(path = "/members/{id}/photo", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "removeTeamMemberPhoto", summary = "Removes the person's photo. `team.manage`.")
    @ApiResponse(responseCode = "200", description = "The person without a photo.")
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
            description = "The person has no photo: `invalid_state`. They changed at the same moment: `conflict`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<TeamMemberResponse> removePhoto(@PathVariable String id) {
        return answer(photos.remove(PathIds.of(id)));
    }

    @GetMapping(path = "/sign-in-accounts", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "listTeamSignInAccounts",
            summary =
                    "The salon's active sign-in accounts, with who each is linked to. `team.manage` and `staff.list`.")
    @ApiResponse(responseCode = "200", description = "The accounts.")
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Without `team.manage` and `staff.list`: `permission_denied`. A suspended salon:"
                    + " `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "The hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<SignInAccountListResponse> signInAccounts() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(SignInAccountListResponse.from(team.signInAccounts()));
    }

    private static ResponseEntity<TeamMemberResponse> answer(TeamMemberService.MemberView view) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(TeamMemberResponse.from(view));
    }
}
