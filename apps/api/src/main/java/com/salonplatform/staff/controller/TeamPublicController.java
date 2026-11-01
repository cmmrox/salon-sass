package com.salonplatform.staff.controller;

import com.salonplatform.shared.web.ApiPaths;
import com.salonplatform.staff.dto.PublicTeamResponse;
import com.salonplatform.staff.service.MemberPhotos;
import com.salonplatform.staff.service.PublicTeamService;
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
 * What anyone may read of the team, with no sign-in (F06-S01 AC-9, AC-10): the profiles shown for online booking, which
 * the customer app reads (F15), and team photos. On the salon's hostname only; the salon is the hostname's.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/team")
@Tag(name = "staff")
class TeamPublicController {

    private static final String PROBLEM = "#/components/schemas/Problem";

    /** The web's salon-hostname caching (docs/engineering/web.md): nothing about a salon is stored. */
    private static final String NOT_STORED = "private, no-cache, no-store, max-age=0, must-revalidate";

    private final PublicTeamService team;
    private final MemberPhotos photos;

    TeamPublicController(PublicTeamService team, MemberPhotos photos) {
        this.team = team;
        this.photos = photos;
    }

    @GetMapping(path = "/public", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "getPublicTeam",
            summary = "The people customers can see for online booking. Public.",
            description = "Those shown for online booking who perform at least one service bookable online, in name"
                    + " order, with those services' ids. Never a hidden or removed person, an account or a version.")
    @ApiResponse(responseCode = "200", description = "The team.")
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
    ResponseEntity<PublicTeamResponse> team() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(PublicTeamResponse.from(team.team()));
    }

    @GetMapping(path = "/photos/{id}", produces = "image/jpeg")
    @Operation(operationId = "getTeamMemberPhoto", summary = "A team member's photo. Public.")
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
        MemberPhotos.Stored stored = photos.read(PathIds.of(id));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, NOT_STORED)
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(stored.mediaType()))
                .body(stored.bytes());
    }
}
