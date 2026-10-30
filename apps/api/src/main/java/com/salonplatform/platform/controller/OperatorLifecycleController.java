package com.salonplatform.platform.controller;

import com.salonplatform.platform.domain.Paging;
import com.salonplatform.platform.dto.ClosureRequest;
import com.salonplatform.platform.dto.ExportView;
import com.salonplatform.platform.dto.LifecyclePage;
import com.salonplatform.platform.dto.LifecycleRequest;
import com.salonplatform.platform.dto.SalonDetail;
import com.salonplatform.platform.service.ExportDownloadService;
import com.salonplatform.platform.service.SalonLifecycleService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A salon's lifecycle in the console (F03-S03): suspend, reactivate and close, the lifecycle history,
 * and a closed salon's export status and archive. Console host only (OperatorHostFilter); Admin only.
 */
@RestController
@Tag(name = "platform")
@RequestMapping(path = "/api/v1/operator/salons/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
class OperatorLifecycleController {

    private final SalonLifecycleService lifecycle;
    private final ExportDownloadService exports;

    OperatorLifecycleController(SalonLifecycleService lifecycle, ExportDownloadService exports) {
        this.lifecycle = lifecycle;
        this.exports = exports;
    }

    @PostMapping(path = "/suspension", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SalonDetail> suspend(@PathVariable String id, @RequestBody LifecycleRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(lifecycle.suspend(
                        OperatorRequests.id(id),
                        new SalonLifecycleService.ChangeInput(request.version(), request.reason())));
    }

    @DeleteMapping(path = "/suspension", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SalonDetail> reactivate(@PathVariable String id, @RequestBody LifecycleRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(lifecycle.reactivate(
                        OperatorRequests.id(id),
                        new SalonLifecycleService.ChangeInput(request.version(), request.reason())));
    }

    @PostMapping(path = "/closure", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SalonDetail> close(@PathVariable String id, @RequestBody ClosureRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(lifecycle.close(
                        OperatorRequests.id(id),
                        new SalonLifecycleService.ClosureInput(
                                request.version(), request.confirmSlug(), request.reason())));
    }

    @GetMapping("/lifecycle")
    ResponseEntity<LifecyclePage> history(
            @PathVariable String id,
            @Parameter(schema = @Schema(type = "integer", minimum = "0", maximum = "100000", defaultValue = "0"))
                    @RequestParam(required = false)
                    String page,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
                    @RequestParam(required = false)
                    String size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(lifecycle.history(OperatorRequests.id(id), Paging.of(page, size, null)));
    }

    @GetMapping("/export")
    ResponseEntity<ExportView> export(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(exports.status(OperatorRequests.id(id)));
    }

    @GetMapping(
            path = "/export/archive",
            produces = {"application/zip", MediaType.APPLICATION_JSON_VALUE})
    @ApiResponse(
            responseCode = "200",
            description = "The export archive",
            content = @Content(mediaType = "application/zip", schema = @Schema(type = "string", format = "binary")))
    ResponseEntity<byte[]> archive(@PathVariable String id) {
        return attachment(exports.archiveForOperator(OperatorRequests.id(id)));
    }

    static ResponseEntity<byte[]> attachment(ExportDownloadService.Archive archive) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(archive.fileName())
                                .build()
                                .toString())
                .header("X-Content-SHA256", archive.sha256())
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(archive.bytes());
    }
}
