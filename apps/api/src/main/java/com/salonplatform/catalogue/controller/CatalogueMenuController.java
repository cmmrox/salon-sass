package com.salonplatform.catalogue.controller;

import com.salonplatform.catalogue.dto.MenuResponse;
import com.salonplatform.catalogue.service.MenuService;
import com.salonplatform.catalogue.service.ServicePhotos;
import com.salonplatform.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What anyone may read of the catalogue, with no sign-in (F05-S01 AC-10, AC-11): the salon's bookable menu, which
 * the customer app reads (F15), and its service photos. On the salon's hostname only; the salon is the hostname's.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/catalogue")
@Tag(name = "catalogue")
class CatalogueMenuController {

    private static final String PROBLEM = "#/components/schemas/Problem";

    /** The web's salon-hostname caching (docs/engineering/web.md): nothing about a salon is stored. */
    private static final String NOT_STORED = "private, no-cache, no-store, max-age=0, must-revalidate";

    private final MenuService menus;
    private final ServicePhotos photos;

    CatalogueMenuController(MenuService menus, ServicePhotos photos) {
        this.menus = menus;
        this.photos = photos;
    }

    @GetMapping(path = "/menu", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "getCatalogueMenu",
            summary = "The salon's bookable menu. Public.",
            description = "Its categories in its order, each with its services that are bookable online, by name. A"
                    + " category without such services is left out. Amounts are minor units of `currency`. A service"
                    + " with variants gives each with its price and time, and its lowest and highest values; `popular`"
                    + " lists the Popular services.")
    @ApiResponse(responseCode = "200", description = "The menu.")
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
    ResponseEntity<MenuResponse> menu() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(MenuResponse.from(menus.menu()));
    }

    @GetMapping(path = "/photos/{id}", produces = "image/jpeg")
    @Operation(operationId = "getCatalogueServicePhoto", summary = "A service photo of the salon. Public.")
    @ApiResponse(
            responseCode = "200",
            description = "The photo: a JPEG, never stored by a cache, with `nosniff`.",
            content = @Content(mediaType = "image/jpeg", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(
            responseCode = "403",
            description = "A suspended salon: `salon_suspended`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "404",
            description = "No such photo of the hostname's salon (a replaced or removed one included), or the hostname"
                    + " names no salon: `not_found`, the same answer each time.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = PROBLEM)))
    ResponseEntity<byte[]> photo(@PathVariable String id) {
        ServicePhotos.Stored stored = photos.read(PathIds.of(id));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, NOT_STORED)
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(stored.mediaType()))
                .body(stored.bytes());
    }
}
