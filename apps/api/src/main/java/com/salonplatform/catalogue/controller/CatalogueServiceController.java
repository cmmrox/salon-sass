package com.salonplatform.catalogue.controller;

import com.salonplatform.catalogue.dto.ServiceListResponse;
import com.salonplatform.catalogue.dto.ServiceOnlineRequest;
import com.salonplatform.catalogue.dto.ServicePhotoUploadForm;
import com.salonplatform.catalogue.dto.ServiceRequest;
import com.salonplatform.catalogue.dto.ServiceResponse;
import com.salonplatform.catalogue.dto.UpdateServiceRequest;
import com.salonplatform.catalogue.service.SalonServices;
import com.salonplatform.catalogue.service.ServicePhotos;
import com.salonplatform.shared.web.ApiPaths;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
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
 * The salon's services (F05-S01, CAT-01, CAT-04), on the salon's hostname; the salon is the hostname's.
 * Permissions are checked by the services.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/catalogue/services", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "catalogue")
class CatalogueServiceController {

    private static final String PROBLEM = "#/components/schemas/Problem";

    private final SalonServices services;
    private final ServicePhotos photos;

    CatalogueServiceController(SalonServices services, ServicePhotos photos) {
        this.services = services;
        this.photos = photos;
    }

    @GetMapping
    @Operation(
            operationId = "listCatalogueServices",
            summary = "Every service of the salon with its current terms, by name. `catalogue.read`.")
    @ApiResponse(responseCode = "200", description = "The services, at most 300.")
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
    ResponseEntity<ServiceListResponse> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ServiceListResponse.from(services.list()));
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getCatalogueService", summary = "One service with its current terms. `catalogue.read`.")
    @ApiResponse(responseCode = "200", description = "The service.")
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
            description = "No such service of the hostname's salon, or the hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<ServiceResponse> get(@PathVariable String id) {
        return answer(services.get(PathIds.of(id)));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "createCatalogueService",
            summary = "Creates a service with its first terms version. `catalogue.price.change`.")
    @ApiResponse(responseCode = "201", description = "The service.")
    @ApiResponse(
            responseCode = "400",
            description =
                    "A field broke a rule (a category that is not the salon's included), or another service has the"
                            + " name regardless of case: `bad_request`, with `errors` naming each field. Nothing is saved.",
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
            description =
                    "The salon has 300 services: `limit_reached`. A change made at the same moment won: `conflict`."
                            + " Nothing is saved.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<ServiceResponse> create(@RequestBody ServiceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(ServiceResponse.from(services.create(request.input())));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "saveCatalogueService",
            summary = "Saves every field of a service. `catalogue.price.change`.",
            description = "A change of price, time or buffer creates a new terms version and makes it current; new"
                    + " bookings use it and existing bookings keep theirs. Any other change creates none.")
    @ApiResponse(responseCode = "200", description = "The service as saved.")
    @ApiResponse(
            responseCode = "400",
            description =
                    "A field broke a rule (a category that is not the salon's included), or another service has the"
                            + " name regardless of case: `bad_request`, with `errors` naming each field. Nothing is saved.",
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
            description = "No such service of the hostname's salon, or the hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description =
                    "`version` is not the service's: `conflict`. Nothing changed: `invalid_state`. Nothing is saved.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<ServiceResponse> save(@PathVariable String id, @RequestBody UpdateServiceRequest request) {
        return answer(services.update(PathIds.of(id), request.input(), request.version()));
    }

    @PostMapping("/{id}/duplicate")
    @Operation(
            operationId = "duplicateCatalogueService",
            summary = "Copies a service, hidden online. `catalogue.price.change`.",
            description = "Named `<name> (copy)`, or `(copy 2)` and upward when taken, shortened to fit 40"
                    + " characters, with its own first terms version and its own copy of the photo.")
    @ApiResponse(responseCode = "201", description = "The copy.")
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
            description = "No such service of the hostname's salon, or the hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description = "The salon has 300 services: `limit_reached`. Nothing is saved.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<ServiceResponse> duplicate(@PathVariable String id) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(ServiceResponse.from(services.duplicate(PathIds.of(id))));
    }

    @PutMapping(path = "/{id}/online", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "setCatalogueServiceOnline",
            summary = "Shows a service online or hides it. Nothing else changes. `catalogue.price.change`.")
    @ApiResponse(responseCode = "200", description = "The service.")
    @ApiResponse(
            responseCode = "400",
            description = "`bookableOnline` or `version` is missing: `bad_request`, with `errors` naming it.",
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
            description = "No such service of the hostname's salon, or the hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description = "`version` is not the service's: `conflict`. It is already so: `invalid_state`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<ServiceResponse> online(@PathVariable String id, @RequestBody ServiceOnlineRequest request) {
        return answer(services.showOnline(PathIds.of(id), request.bookableOnline(), request.version()));
    }

    @DeleteMapping("/{id}")
    @Operation(
            operationId = "deleteCatalogueService",
            summary = "Deletes a service no upcoming booking uses. `catalogue.price.change`.",
            description = "It leaves the workspace and the menu and frees its name. Its terms versions are kept for"
                    + " the bookings that use them.")
    @ApiResponse(responseCode = "204", description = "Deleted.")
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
            description = "Without `catalogue.price.change`: `permission_denied`. A support session:"
                    + " `support_read_only`. A read-only salon: `salon_read_only`. A suspended salon:"
                    + " `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "No such service of the hostname's salon, or the hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description =
                    "Upcoming bookings use it: `in_use`; hide it from online booking instead. `version` is not the"
                            + " service's: `conflict`. Nothing is deleted.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<Void> delete(@PathVariable String id, @RequestParam(required = false) Long version) {
        services.delete(PathIds.of(id), version);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PutMapping(
            path = "/{id}/photo",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "uploadCatalogueServicePhoto",
            summary = "Sets the service's photo, replacing any earlier one. `catalogue.price.change`.",
            description = "A PNG, JPEG or WebP image of up to 5 MB and at least 320 x 320, told by its content, not"
                    + " its name or declared type. It is cropped to its centre square and kept as a JPEG of at most"
                    + " 800 x 800, re-encoded, so no metadata is kept. The replaced photo is no longer served.")
    @ApiResponse(responseCode = "200", description = "The service with its new photo.")
    @ApiResponse(
            responseCode = "400",
            description = "The file is missing, not one of the three types, too large, too small, or cannot be decoded:"
                    + " `bad_request`, with `errors` naming `file`. The service keeps its previous photo.",
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
            description = "No such service of the hostname's salon, or the hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "413",
            description = "The request is over the 10 MB request limit: `request_rejected`. Nothing is kept.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering, or too many uploads are being decoded at once:"
                    + " `service_unavailable`. Try again.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content =
                    @Content(
                            mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                            schema = @Schema(implementation = ServicePhotoUploadForm.class)))
    ResponseEntity<ServiceResponse> uploadPhoto(
            @PathVariable String id,
            @Parameter(hidden = true) @RequestPart(value = "file", required = false) MultipartFile file)
            throws IOException {
        if (file == null) {
            throw new ApplicationProblemException(
                    400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("file", "is required")));
        }
        return answer(photos.upload(PathIds.of(id), file.getBytes()));
    }

    @DeleteMapping("/{id}/photo")
    @Operation(
            operationId = "removeCatalogueServicePhoto",
            summary = "Removes the service's photo. `catalogue.price.change`.")
    @ApiResponse(responseCode = "200", description = "The service without a photo.")
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
            description = "No such service of the hostname's salon, or the hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description = "The service has no photo: `invalid_state`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<ServiceResponse> removePhoto(@PathVariable String id) {
        return answer(photos.remove(PathIds.of(id)));
    }

    private static ResponseEntity<ServiceResponse> answer(SalonServices.ServiceView view) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ServiceResponse.from(view));
    }
}
