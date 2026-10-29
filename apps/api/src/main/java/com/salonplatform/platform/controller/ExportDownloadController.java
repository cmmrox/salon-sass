package com.salonplatform.platform.controller;

import com.salonplatform.platform.service.ExportDownloads;
import com.salonplatform.shared.web.NotFoundException;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Owner's export download (F03-S03): on the bare platform host only (any other host is 404 before
 * anything is read), with no session. The link's token is the only credential; a malformed, wrong,
 * altered or expired token is 404 exactly as for a salon that never existed. The request log and
 * traces show the path with the token replaced.
 */
@RestController
@Tag(name = "platform")
@RequestMapping(path = "/api/v1/exports", produces = MediaType.APPLICATION_JSON_VALUE)
class ExportDownloadController {

    private final ExportDownloads exports;
    private final String platformDomain;

    ExportDownloadController(ExportDownloads exports, @Value("${salon.platform-domain}") String platformDomain) {
        this.exports = exports;
        this.platformDomain = platformDomain.strip().toLowerCase(Locale.ROOT);
    }

    @GetMapping(
            path = "/{token}",
            produces = {"application/zip", MediaType.APPLICATION_JSON_VALUE})
    @ApiResponse(
            responseCode = "200",
            description = "The export archive",
            content = @Content(mediaType = "application/zip", schema = @Schema(type = "string", format = "binary")))
    ResponseEntity<byte[]> download(@PathVariable String token, HttpServletRequest request) {
        platformHost(request);
        return OperatorLifecycleController.attachment(exports.archiveForToken(token));
    }

    /** What the download page explains: when the archive was made and is deleted, its size and checksum. */
    @GetMapping("/{token}/metadata")
    ResponseEntity<ExportDownloads.LinkView> metadata(@PathVariable String token, HttpServletRequest request) {
        platformHost(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(exports.linkView(token));
    }

    /** Exactly the platform domain, any letter case, with no port or one valid decimal port. */
    private void platformHost(HttpServletRequest request) {
        String host = request.getHeader(HttpHeaders.HOST);
        if (host == null) {
            throw new NotFoundException();
        }
        String value = host.strip().toLowerCase(Locale.ROOT);
        int colon = value.lastIndexOf(':');
        String name = colon < 0 ? value : value.substring(0, colon);
        if ((colon >= 0 && !value.substring(colon + 1).matches("[0-9]{1,5}")) || !name.equals(platformDomain)) {
            throw new NotFoundException();
        }
    }
}
