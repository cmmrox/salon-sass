package com.salonplatform.branding.controller;

import com.salonplatform.branding.domain.BrandRules;
import com.salonplatform.branding.dto.SalonBrandDraftResponse;
import com.salonplatform.branding.dto.SalonBrandImageUploadForm;
import com.salonplatform.branding.dto.SalonBrandVersionRequest;
import com.salonplatform.branding.dto.SaveSalonBrandDraftRequest;
import com.salonplatform.branding.dto.UploadedSalonBrandImageResponse;
import com.salonplatform.branding.service.BrandImageService;
import com.salonplatform.branding.service.BrandService;
import com.salonplatform.shared.web.ApiPaths;
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
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
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
 * The salon's brand as its settings staff keep it (F04-S02): the draft, saved, reset, published and
 * discarded, on F04-S01's pattern; its images uploaded and served (decision 086). On the salon's hostname
 * only; the salon is the hostname's, whatever a request sends. Permissions are checked by the services.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/salon/brand")
@Tag(name = "branding")
class SalonBrandController {

    private static final String PROBLEM = "#/components/schemas/Problem";

    /** The salon hostname's caching, the web's (docs/engineering/web.md): nothing about a salon is stored. */
    static final String NOT_STORED = "private, no-cache, no-store, max-age=0, must-revalidate";

    private final BrandService brands;
    private final BrandImageService images;

    SalonBrandController(BrandService brands, BrandImageService images) {
        this.brands = brands;
        this.images = images;
    }

    @GetMapping(path = "/draft", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "getSalonBrandDraft",
            summary = "The salon's brand draft, with whether it has unpublished changes. `settings.read`.")
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
    ResponseEntity<SalonBrandDraftResponse> draft() {
        return answer(brands.draft());
    }

    @PutMapping(
            path = "/draft",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "saveSalonBrandDraft",
            summary = "Saves the brand as the salon's draft, which only settings staff see. `settings.manage`.",
            description = "The name is required, 1 to 60 characters after trimming; the tagline up to 80, empty for"
                    + " none; the colour `#RRGGBB`, kept in upper case; the logo and the cover each the id of an image"
                    + " of this salon of that kind, uploaded first, or null for none.")
    @ApiResponse(responseCode = "200", description = "The draft as saved.")
    @ApiResponse(
            responseCode = "400",
            description =
                    "A field broke a rule, or the body is not the documented shape: `bad_request`, with `errors` naming each field (`brand.colour`). Nothing is saved.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description =
                    "Without `settings.manage`: `permission_denied`. A support session: `support_read_only`. A read-only salon: `salon_read_only`. A suspended salon: `salon_suspended`.",
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
    ResponseEntity<SalonBrandDraftResponse> save(@Valid @RequestBody SaveSalonBrandDraftRequest request) {
        var brand = request.brand();
        return answer(brands.save(
                request.version(),
                new BrandRules.Input(brand.name(), brand.tagline(), brand.colour(), brand.logo(), brand.cover())));
    }

    @PostMapping(
            path = "/draft/reset",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "resetSalonBrandDraft",
            summary = "Sets the draft to the default branding: colour `#D6086A`, no tagline, logo or cover; the name"
                    + " kept. A draft change, until published. `settings.manage`.")
    @ApiResponse(responseCode = "200", description = "The draft with the defaults.")
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
            description =
                    "Without `settings.manage`: `permission_denied`. A support session: `support_read_only`. A read-only salon: `salon_read_only`. A suspended salon: `salon_suspended`.",
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
    ResponseEntity<SalonBrandDraftResponse> reset(@Valid @RequestBody SalonBrandVersionRequest request) {
        return answer(brands.reset(request.version()));
    }

    @PostMapping(
            path = "/publication",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "publishSalonBrand",
            summary = "Makes the draft the published brand, in one step. `settings.manage`.")
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
            description =
                    "Without `settings.manage`: `permission_denied`. A support session: `support_read_only`. A read-only salon: `salon_read_only`. A suspended salon: `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "The hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description =
                    "The draft changed since `version`: `conflict`. Nothing to publish: `invalid_state`. Either way nothing changes.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<SalonBrandDraftResponse> publish(@Valid @RequestBody SalonBrandVersionRequest request) {
        return answer(brands.publish(request.version()));
    }

    @DeleteMapping(
            path = "/draft",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "discardSalonBrandDraft",
            summary = "Returns the draft to the published brand. `settings.manage`.")
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
            description =
                    "Without `settings.manage`: `permission_denied`. A support session: `support_read_only`. A read-only salon: `salon_read_only`. A suspended salon: `salon_suspended`.",
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
    ResponseEntity<SalonBrandDraftResponse> discard(@Valid @RequestBody SalonBrandVersionRequest request) {
        return answer(brands.discard(request.version()));
    }

    @PostMapping(
            path = "/images",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "uploadSalonBrandImage",
            summary = "Uploads a logo or cover image. It does not change the draft: save the draft with its id."
                    + " `settings.manage`.",
            description = "A PNG, JPEG or WebP image of up to 5 MB, told by its content, not its name or declared"
                    + " type. A logo is at least 128 x 128 and is kept as its centre square, a PNG of at most 512 x"
                    + " 512; a cover is at least 800 x 300 and is kept as a JPEG at most 1600 wide. Both are"
                    + " re-encoded, so no metadata is kept. An image unused for 24 hours is removed.")
    @ApiResponse(responseCode = "201", description = "The image, kept.")
    @ApiResponse(
            responseCode = "400",
            description =
                    "`kind` is not `logo` or `cover`, or the file is missing, not one of the three types, too large, too small or cannot be decoded: `bad_request`, with `errors` naming `kind` or `file`. Nothing is kept.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description =
                    "Without `settings.manage`: `permission_denied`. A support session: `support_read_only`. A read-only salon: `salon_read_only`. A suspended salon: `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "The hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "413",
            description = "The request is over the 10 MB request limit: `request_rejected`. Nothing is kept.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content =
                    @Content(
                            mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                            schema = @Schema(implementation = SalonBrandImageUploadForm.class)))
    ResponseEntity<UploadedSalonBrandImageResponse> upload(
            @Parameter(hidden = true) @RequestParam(value = "kind", required = false) String kind,
            @Parameter(hidden = true) @RequestPart(value = "file", required = false) MultipartFile file)
            throws IOException {
        if (file == null) {
            throw new ApplicationProblemException(
                    400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("file", "is required")));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, NOT_STORED)
                .body(UploadedSalonBrandImageResponse.from(images.upload(kind, file.getBytes())));
    }

    @GetMapping(
            path = "/images/{id}",
            produces = {"image/png", "image/jpeg"})
    @Operation(
            operationId = "getSalonBrandImage",
            summary = "A brand image of the salon: to anyone when the published brand uses it, otherwise only with"
                    + " `settings.read`.")
    @ApiResponse(
            responseCode = "200",
            description = "The image: a PNG logo or a JPEG cover, never stored by a cache, with `nosniff`.",
            content = {
                @Content(mediaType = "image/png", schema = @Schema(type = "string", format = "binary")),
                @Content(mediaType = "image/jpeg", schema = @Schema(type = "string", format = "binary"))
            })
    @ApiResponse(
            responseCode = "403",
            description = "A suspended salon: `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description =
                    "No such image of the hostname's salon, or one the caller may not see (a draft image without `settings.read`), or the hostname names no salon: `not_found`, the same answer each time.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<byte[]> image(@PathVariable String id) {
        UUID image;
        try {
            image = UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            throw new NotFoundException();
        }
        BrandImageService.Stored stored = images.read(image);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, NOT_STORED)
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(stored.mediaType()))
                .body(stored.bytes());
    }

    /** A draft is staff-only data: never stored by a cache. */
    private static ResponseEntity<SalonBrandDraftResponse> answer(
            com.salonplatform.branding.service.BrandDraftView view) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, NOT_STORED)
                .body(SalonBrandDraftResponse.from(view));
    }
}
