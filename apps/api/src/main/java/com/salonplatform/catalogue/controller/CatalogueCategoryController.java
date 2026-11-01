package com.salonplatform.catalogue.controller;

import com.salonplatform.catalogue.dto.CategoryListResponse;
import com.salonplatform.catalogue.dto.CategoryNameRequest;
import com.salonplatform.catalogue.dto.CategoryResponse;
import com.salonplatform.catalogue.dto.MoveCategoryRequest;
import com.salonplatform.catalogue.dto.RenameCategoryRequest;
import com.salonplatform.catalogue.service.CategoryService;
import com.salonplatform.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * The salon's categories in its order (F05-S01, CAT-02), on the salon's hostname; the salon is the hostname's.
 * Permissions are checked by the service.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/catalogue/categories", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "catalogue")
class CatalogueCategoryController {

    private static final String PROBLEM = "#/components/schemas/Problem";

    private final CategoryService categories;

    CatalogueCategoryController(CategoryService categories) {
        this.categories = categories;
    }

    @GetMapping
    @Operation(
            operationId = "listCatalogueCategories",
            summary = "The categories in the salon's order. `catalogue.read`.")
    @ApiResponse(responseCode = "200", description = "The categories, at most 50.")
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
    ResponseEntity<CategoryListResponse> list() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(CategoryListResponse.from(categories.list()));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "addCatalogueCategory",
            summary = "Adds a category at the end of the list. `catalogue.price.change`.")
    @ApiResponse(responseCode = "201", description = "The category.")
    @ApiResponse(
            responseCode = "400",
            description = "The name is not 2 to 30 characters, or another category has it regardless of case:"
                    + " `bad_request`, with `errors` naming `name`. Nothing is saved.",
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
            description = "The salon has 50 categories: `limit_reached`. A change made at the same moment won:"
                    + " `conflict`. Nothing is saved.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<CategoryResponse> add(@RequestBody CategoryNameRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(CategoryResponse.from(categories.add(request.name())));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "renameCatalogueCategory", summary = "Renames a category. `catalogue.price.change`.")
    @ApiResponse(responseCode = "200", description = "The category, renamed.")
    @ApiResponse(
            responseCode = "400",
            description = "The name is not 2 to 30 characters, or another category has it regardless of case, or"
                    + " `version` is missing: `bad_request`, with `errors` naming the field. Nothing is saved.",
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
            description = "No such category of the hostname's salon, or the hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description = "`version` is not the category's: `conflict`. The name is unchanged: `invalid_state`."
                    + " Nothing is saved.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<CategoryResponse> rename(@PathVariable String id, @RequestBody RenameCategoryRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(CategoryResponse.from(categories.rename(PathIds.of(id), request.name(), request.version())));
    }

    @PostMapping(path = "/{id}/move", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "moveCatalogueCategory",
            summary = "Moves a category one place up or down, at once. `catalogue.price.change`.",
            description = "Customers see the new order straight away.")
    @ApiResponse(responseCode = "200", description = "The categories in their new order.")
    @ApiResponse(
            responseCode = "400",
            description = "`direction` is not `up` or `down`, or `listVersion` is missing: `bad_request`, with"
                    + " `errors` naming the field.",
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
            description = "No such category of the hostname's salon, or the hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description = "`listVersion` is not the list's: `conflict`. The first category moved up or the last"
                    + " down: `invalid_state`. Nothing is saved.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<CategoryListResponse> move(@PathVariable String id, @RequestBody MoveCategoryRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(CategoryListResponse.from(
                        categories.move(PathIds.of(id), request.direction(), request.listVersion())));
    }

    @DeleteMapping("/{id}")
    @Operation(
            operationId = "deleteCatalogueCategory",
            summary = "Deletes a category that has no services. `catalogue.price.change`.")
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
            description = "No such category of the hostname's salon, or the hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description = "It has services: `in_use`. It is the last category: `invalid_state`. `version` is not the"
                    + " category's: `conflict`. Nothing is deleted.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<Void> delete(@PathVariable String id, @RequestParam(required = false) Long version) {
        categories.delete(PathIds.of(id), version);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
