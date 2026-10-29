package com.salonplatform.platform.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.salonplatform.platform.application.SupportSessions;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A support session on the salon's own host (F03-S04): the workspace BFF exchanges a single-use entry
 * code for the read-only support credential, and the session ends itself. The salon is the host's;
 * another host has no salon and answers 404.
 */
@RestController
@RequestMapping(path = "/api/v1/support", produces = MediaType.APPLICATION_JSON_VALUE)
class SupportEntryController {

    private final SupportSessions sessions;

    SupportEntryController(SupportSessions sessions) {
        this.sessions = sessions;
    }

    @Schema(
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {"code"})
    record EntryRequest(
            @Schema(minLength = 43, maxLength = 43) String code) {
        @JsonAnySetter
        void unknown(String field, Object ignored) {
            throw OperatorRequests.unexpected(field);
        }
    }

    @PostMapping(path = "/entry", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SupportSessions.SupportCredential> enterSupportSession(@RequestBody EntryRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sessions.enter(request.code()));
    }

    @DeleteMapping("/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    ResponseEntity<Void> endCurrentSupportSession() {
        sessions.endCurrent();
        return ResponseEntity.noContent().build();
    }
}
