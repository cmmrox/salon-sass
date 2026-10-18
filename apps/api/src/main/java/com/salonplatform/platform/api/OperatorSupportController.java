package com.salonplatform.platform.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.salonplatform.platform.application.Paging;
import com.salonplatform.platform.application.SupportSessions;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;

import org.springframework.http.CacheControl;
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
 * Support sessions in the console (F03-S04): Support starts one for a salon and ends its own; Admin
 * sees and revokes every one. Console host only (OperatorHostFilter).
 */
@RestController
@RequestMapping(path = "/api/v1/operator", produces = MediaType.APPLICATION_JSON_VALUE)
class OperatorSupportController {

    private final SupportSessions sessions;

    OperatorSupportController(SupportSessions sessions) {
        this.sessions = sessions;
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE, requiredProperties = {"reason"})
    record StartRequest(@Schema(minLength = 10, maxLength = 500) String reason,
            @Schema(minimum = "5", maximum = "120", defaultValue = "30") Integer durationMinutes) {
        @JsonAnySetter
        void unknown(String field, Object ignored) {
            throw OperatorRequests.unexpected(field);
        }
    }

    @PostMapping(path = "/salons/{id}/support-sessions", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SupportSessions.SupportEntry> startSupportSession(@PathVariable String id, @RequestBody StartRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(sessions.start(
                OperatorRequests.id(id), new SupportSessions.StartInput(request.reason(), request.durationMinutes())));
    }

    @PostMapping("/support-sessions/{id}/entry-codes")
    ResponseEntity<SupportSessions.SupportEntry> issueSupportEntryCode(@PathVariable String id) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(sessions.entryCode(OperatorRequests.id(id)));
    }

    @DeleteMapping("/support-sessions/{id}")
    ResponseEntity<Void> endSupportSession(@PathVariable String id) {
        sessions.end(OperatorRequests.id(id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/support-sessions")
    ResponseEntity<SupportSessions.SupportSessionPage> listSupportSessions(
            @Parameter(schema = @Schema(type = "integer", minimum = "0", maximum = "100000", defaultValue = "0"))
            @RequestParam(required = false) String page,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
            @RequestParam(required = false) String size,
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @RequestParam(required = false) String salonId,
            @Parameter(schema = @Schema(type = "string", allowableValues = {"active", "ended"}))
            @RequestParam(required = false) String state) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(sessions.list(Paging.of(page, size, null), salonId, state));
    }
}
