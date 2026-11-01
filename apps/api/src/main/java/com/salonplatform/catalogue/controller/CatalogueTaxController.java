package com.salonplatform.catalogue.controller;

import com.salonplatform.catalogue.dto.NoTax;
import com.salonplatform.catalogue.dto.PublicTaxResponse;
import com.salonplatform.catalogue.dto.SaveTaxRequest;
import com.salonplatform.catalogue.dto.TaxHistoryResponse;
import com.salonplatform.catalogue.dto.TaxSettingsResponse;
import com.salonplatform.catalogue.service.TaxSettingsService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.NullNode;

/**
 * The salon's tax settings (F05-S03, CAT-05): public as customers may know them, and as its settings staff keep them,
 * with their history. On the salon's hostname only; the salon is the hostname's. Permissions are checked by the
 * service.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/catalogue/tax", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "catalogue")
class CatalogueTaxController {

    private static final String PROBLEM = "#/components/schemas/Problem";

    private final TaxSettingsService tax;

    CatalogueTaxController(TaxSettingsService tax) {
        this.tax = tax;
    }

    @GetMapping
    @Operation(
            operationId = "getCatalogueTax",
            summary = "The salon's current tax: which parts its prices include, at what rates. Public.",
            description = "JSON `null` before the salon first saves its tax settings: nothing is charged. Never the"
                    + " VAT registration number. A booking or a sale made now keeps `versionId`.")
    @ApiResponse(
            responseCode = "200",
            description = "The current tax, or `null`.",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema =
                                    @Schema(
                                            oneOf = {PublicTaxResponse.class, NoTax.class},
                                            description = "The current tax; null before the first save.")))
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
    ResponseEntity<Object> publicTax() {
        Object body = tax.publicTax().<Object>map(PublicTaxResponse::from).orElse(NullNode.getInstance());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping("/settings")
    @Operation(
            operationId = "getCatalogueTaxSettings",
            summary = "The tax settings as settings staff keep them, and what configuration offers. `settings.read`.")
    @ApiResponse(responseCode = "200", description = "The settings.")
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
    ResponseEntity<TaxSettingsResponse> settings() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(TaxSettingsResponse.from(tax.settings()));
    }

    @PutMapping(path = "/settings", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "saveCatalogueTaxSettings",
            summary = "Saves the tax settings as a new version, current at once for new bookings and sales."
                    + " `settings.manage`.",
            description = "The version copies the VAT name and rate and the service charge's name configuration gives"
                    + " now. Existing bookings and sales keep the version they were made under; earlier versions are"
                    + " never changed.")
    @ApiResponse(responseCode = "200", description = "The settings as saved, with the new version's number.")
    @ApiResponse(
            responseCode = "400",
            description = "A field broke a rule, or the body is not the documented shape: `bad_request`, with"
                    + " `errors` naming each field (`vat.registrationNumber`, `serviceCharge.percent`). Nothing is"
                    + " saved.",
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
            description = "`version` is not the current version: `conflict`. The new version would be the current"
                    + " one, with the same configured names and rate: `invalid_state`. Nothing is saved.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<TaxSettingsResponse> save(@RequestBody SaveTaxRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(TaxSettingsResponse.from(tax.save(
                        request.version(),
                        request.vat() == null ? null : request.vat().input(),
                        request.serviceCharge() == null
                                ? null
                                : request.serviceCharge().input())));
    }

    @GetMapping("/history")
    @Operation(
            operationId = "getCatalogueTaxHistory",
            summary = "The tax settings' versions, newest first, paginated. `settings.read`.")
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
    ResponseEntity<TaxHistoryResponse> history(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(TaxHistoryResponse.from(tax.history(page, size)));
    }
}
