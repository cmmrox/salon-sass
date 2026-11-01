package com.salonplatform.catalogue.controller;

import com.salonplatform.catalogue.dto.ProductListResponse;
import com.salonplatform.catalogue.dto.ProductRequest;
import com.salonplatform.catalogue.dto.ProductResponse;
import com.salonplatform.catalogue.dto.UpdateProductRequest;
import com.salonplatform.catalogue.service.ProductService;
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
 * The salon's products (F05-S03, INV-01), on the salon's hostname; the salon is the hostname's. Permissions are
 * checked by the service.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/catalogue/products", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "catalogue")
class CatalogueProductController {

    private static final String PROBLEM = "#/components/schemas/Problem";
    private static final String CHANGE_REFUSED = "Without `catalogue.price.change`: `permission_denied`. A support"
            + " session: `support_read_only`. A read-only salon: `salon_read_only`. A suspended salon:"
            + " `salon_suspended`.";

    private final ProductService products;

    CatalogueProductController(ProductService products) {
        this.products = products;
    }

    @GetMapping
    @Operation(operationId = "listCatalogueProducts", summary = "The products, by name. `catalogue.read`.")
    @ApiResponse(responseCode = "200", description = "The products, at most 300.")
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
    ResponseEntity<ProductListResponse> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ProductListResponse.from(products.list()));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "addCatalogueProduct", summary = "Adds a product. `catalogue.price.change`.")
    @ApiResponse(responseCode = "201", description = "The product.")
    @ApiResponse(
            responseCode = "400",
            description = "The name is not 2 to 40 characters or another product has it regardless of case, the price"
                    + " is not a whole Rs. 1 to Rs. 500,000, or a setting is missing or not true or false:"
                    + " `bad_request`, with `errors` naming each field. Nothing is saved.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = CHANGE_REFUSED,
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "The hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description = "The salon has 300 products: `limit_reached`. A change made at the same moment won:"
                    + " `conflict`. Nothing is saved.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<ProductResponse> add(@RequestBody ProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(ProductResponse.from(products.add(request.input())));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "saveCatalogueProduct",
            summary = "Saves a product's name, price and settings. `catalogue.price.change`.",
            description = "Past sales keep what they copied; new sales use the new price.")
    @ApiResponse(responseCode = "200", description = "The product, saved.")
    @ApiResponse(
            responseCode = "400",
            description = "A field broke a rule, as for an added product, or `version` is missing: `bad_request`,"
                    + " with `errors` naming each field. Nothing is saved.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "401",
            description = "No valid session for this salon: `invalid_session`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "403",
            description = CHANGE_REFUSED,
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "No such product of the hostname's salon, or the hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description = "`version` is not the product's: `conflict`. Nothing changes: `invalid_state`. Nothing is"
                    + " saved.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<ProductResponse> save(@PathVariable String id, @RequestBody UpdateProductRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ProductResponse.from(products.save(PathIds.of(id), request.input(), request.version())));
    }

    @DeleteMapping("/{id}")
    @Operation(
            operationId = "deleteCatalogueProduct",
            summary = "Deletes a product: it leaves checkout and its name is free. `catalogue.price.change`.",
            description = "Past sales keep what they copied.")
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
            description = CHANGE_REFUSED,
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "No such product of the hostname's salon, or the hostname names no salon: `not_found`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "409",
            description = "`version` is not the product's: `conflict`. Nothing is deleted.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<Void> delete(@PathVariable String id, @RequestParam(required = false) Long version) {
        products.delete(PathIds.of(id), version);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
