package com.salonplatform.platform.api;

import com.salonplatform.platform.application.Paging;
import com.salonplatform.platform.application.SupportSessions;
import com.salonplatform.shared.api.ApiPaths;
import com.salonplatform.shared.api.NotFoundException;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Owner's Support access (F03-S04): the salon's support sessions, active and past, and revocation
 * of an active one. Salon host only; Owner only ({@code support.manage}).
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/salon/support-sessions", produces = MediaType.APPLICATION_JSON_VALUE)
class SalonSupportController {

    private final SupportSessions sessions;

    SalonSupportController(SupportSessions sessions) {
        this.sessions = sessions;
    }

    @GetMapping
    ResponseEntity<SupportSessions.SalonSupportSessionPage> listSalonSupportSessions(
            @Parameter(schema = @Schema(type = "integer", minimum = "0", maximum = "100000", defaultValue = "0"))
                    @RequestParam(required = false)
                    String page,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
                    @RequestParam(required = false)
                    String size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(sessions.salonList(Paging.of(page, size, null)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    ResponseEntity<Void> revokeSalonSupportSession(@PathVariable String id) {
        UUID session;
        try {
            session = UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            throw new NotFoundException();
        }
        sessions.salonRevoke(session);
        return ResponseEntity.noContent().build();
    }
}
