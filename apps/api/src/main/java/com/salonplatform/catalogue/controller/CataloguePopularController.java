package com.salonplatform.catalogue.controller;

import com.salonplatform.catalogue.dto.PopularSettingRequest;
import com.salonplatform.catalogue.dto.PopularSettingResponse;
import com.salonplatform.catalogue.service.PopularSettings;
import com.salonplatform.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * How the salon's Popular list is made (F05-S02, CAT-06), on the salon's hostname; the salon is the hostname's.
 * Permissions are checked by the service.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/catalogue/popular-setting", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "catalogue")
class CataloguePopularController {

    private static final String PROBLEM = "#/components/schemas/Problem";

    private final PopularSettings settings;

    CataloguePopularController(PopularSettings settings) {
        this.settings = settings;
    }

    @GetMapping
    @Operation(
            operationId = "getCataloguePopularSetting",
            summary = "How the Popular list is made, and the list now. `catalogue.read`.")
    @ApiResponse(responseCode = "200", description = "The setting; `chosen` at version 0 before its first change.")
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Without `catalogue.read`: `permission_denied`. A suspended salon: `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "The hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<PopularSettingResponse> get() {
        return answer(settings.setting());
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "setCataloguePopularSetting",
            summary = "Switches how the Popular list is made, saved at once. `catalogue.price.change`.")
    @ApiResponse(responseCode = "200", description = "The setting as saved, and the list it makes.")
    @ApiResponse(
            responseCode = "400",
            description = "`mode` is not `chosen` or `most_booked`, or `version` is missing: `bad_request`, with"
                    + " `errors` naming it.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = "Without `catalogue.price.change`: `permission_denied`. A support session:"
                    + " `support_read_only`. A read-only salon: `salon_read_only`. A suspended salon:"
                    + " `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "The hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description = "`version` is not the setting's: `conflict`. It is already so: `invalid_state`. Nothing"
                    + " changes.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<PopularSettingResponse> put(@RequestBody PopularSettingRequest request) {
        return answer(settings.switchTo(request.mode(), request.version()));
    }

    private static ResponseEntity<PopularSettingResponse> answer(PopularSettings.PopularView view) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(PopularSettingResponse.from(view));
    }
}
