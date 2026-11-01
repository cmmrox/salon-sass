package com.salonplatform.branding.controller;

import com.salonplatform.branding.domain.ProfileInput;
import com.salonplatform.branding.dto.SalonProfileBranchRequest;
import com.salonplatform.branding.dto.SalonProfileDraftResponse;
import com.salonplatform.branding.dto.SalonProfileRequest;
import com.salonplatform.branding.dto.SalonProfileVersionRequest;
import com.salonplatform.branding.dto.SaveSalonProfileDraftRequest;
import com.salonplatform.branding.service.ProfileService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The salon's business profile as its settings staff keep it (F04-S01): the draft, saved, published and
 * discarded. On the salon's hostname only; the salon is the hostname's, whatever a request sends.
 * Permissions are checked by the service ({@code settings.read}, {@code settings.manage}).
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/salon/profile", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "branding")
class SalonProfileController {

    private static final String PROBLEM = "#/components/schemas/Problem";

    private final ProfileService profiles;

    SalonProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/draft")
    @Operation(
            operationId = "getSalonProfileDraft",
            summary = "The salon's profile draft, with whether it has unpublished changes. `settings.read`.")
    @ApiResponse(responseCode = "200", description = "The draft.")
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
    ResponseEntity<SalonProfileDraftResponse> draft() {
        return answer(SalonProfileDraftResponse.from(profiles.draft()));
    }

    @PutMapping(path = "/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "saveSalonProfileDraft",
            summary = "Saves the profile as the salon's draft, which only settings staff see. `settings.manage`.",
            description = "Text is trimmed and empty means not set. The address is up to 200 characters, the"
                    + " description up to 500 and each link an `https://` address of up to 200. Phone numbers must"
                    + " belong to the configured region (`LK` by default) and are stored in E.164; the WhatsApp"
                    + " number must be a mobile number. A map position needs both coordinates, latitude -90 to 90"
                    + " and longitude -180 to 180, and is rounded to six decimal places.")
    @ApiResponse(responseCode = "200", description = "The draft as saved.")
    @ApiResponse(
            responseCode = "400",
            description = "A field broke a rule, or the body is not the documented shape: `bad_request`, with"
                    + " `errors` naming each field (`profile.branches[0].phone`). Nothing is saved.",
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
            description = "The draft changed since `version`: `conflict`. Nothing is saved.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<SalonProfileDraftResponse> save(@Valid @RequestBody SaveSalonProfileDraftRequest request) {
        return answer(SalonProfileDraftResponse.from(profiles.save(request.version(), input(request.profile()))));
    }

    @PostMapping(path = "/publication", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "publishSalonProfile",
            summary = "Makes the draft the published profile, in one step. `settings.manage`.")
    @ApiResponse(responseCode = "200", description = "The draft, now published.")
    @ApiResponse(
            responseCode = "400",
            description = "The body is not the documented shape: `bad_request`.",
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
            description = "The draft changed since `version`: `conflict`. Nothing to publish: `invalid_state`."
                    + " Either way nothing changes.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<SalonProfileDraftResponse> publish(@Valid @RequestBody SalonProfileVersionRequest request) {
        return answer(SalonProfileDraftResponse.from(profiles.publish(request.version())));
    }

    @DeleteMapping(path = "/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "discardSalonProfileDraft",
            summary = "Returns the draft to the published profile, or to empty before the first publication."
                    + " `settings.manage`.")
    @ApiResponse(responseCode = "200", description = "The draft, discarded.")
    @ApiResponse(
            responseCode = "400",
            description = "The body is not the documented shape: `bad_request`.",
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
            description = "The draft changed since `version`: `conflict`. Nothing changes.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<SalonProfileDraftResponse> discard(@Valid @RequestBody SalonProfileVersionRequest request) {
        return answer(SalonProfileDraftResponse.from(profiles.discard(request.version())));
    }

    /** A draft is staff-only data: never stored by a cache. */
    private static ResponseEntity<SalonProfileDraftResponse> answer(SalonProfileDraftResponse body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ProfileInput input(SalonProfileRequest profile) {
        return new ProfileInput(
                profile.description(),
                profile.links().instagram(),
                profile.links().facebook(),
                profile.links().website(),
                profile.branches().stream().map(SalonProfileController::branch).toList());
    }

    private static ProfileInput.Branch branch(SalonProfileBranchRequest branch) {
        return new ProfileInput.Branch(
                branch.id(),
                branch.address(),
                branch.location() == null
                        ? null
                        : new ProfileInput.Location(
                                branch.location().latitude(), branch.location().longitude()),
                branch.phone(),
                branch.whatsapp());
    }
}
