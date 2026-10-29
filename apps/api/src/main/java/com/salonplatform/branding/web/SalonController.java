package com.salonplatform.branding.web;

import com.salonplatform.branding.application.GetPublishedBrand;
import com.salonplatform.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.V1 + "/salon")
@Tag(name = "branding")
class SalonController {

    private final GetPublishedBrand getPublishedBrand;

    SalonController(GetPublishedBrand getPublishedBrand) {
        this.getPublishedBrand = getPublishedBrand;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getSalon", summary = "The public brand of the salon the request's hostname names.")
    @ApiResponse(responseCode = "200", description = "The salon's public brand.")
    @ApiResponse(
            responseCode = "403",
            description = "The hostname's salon is suspended: `salon_suspended`. Nothing of its brand is returned.",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = "#/components/schemas/Problem")))
    @ApiResponse(
            responseCode = "404",
            description = "The hostname names no salon: `not_found`.",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = "#/components/schemas/Problem")))
    @ApiResponse(
            responseCode = "503",
            description = "The database is down or not answering: `service_unavailable`.",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = "#/components/schemas/Problem")))
    SalonResponse salon() {
        return SalonResponse.from(getPublishedBrand.forCurrentSalon());
    }
}
