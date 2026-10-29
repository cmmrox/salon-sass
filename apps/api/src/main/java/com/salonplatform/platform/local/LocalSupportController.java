package com.salonplatform.platform.local;

import com.salonplatform.platform.service.SupportSessions;
import com.salonplatform.shared.web.NotFoundException;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F03-S04's local test hook: one pass of the support-session expiry job now, at the test clock's
 * instant, so a test can move the clock past a session's expiry and see its end recorded at once.
 * Loopback only, {@code local} profile only, never in the OpenAPI document.
 */
@RestController
@Profile("local")
@Hidden
@RequestMapping("/local/operator")
class LocalSupportController {

    record Pass(Instant at, int ended) {}

    private final SupportSessions sessions;
    private final Clock clock;

    LocalSupportController(SupportSessions sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @PostMapping("/support-sessions/expire")
    ResponseEntity<Pass> expire(HttpServletRequest http) {
        try {
            if (!InetAddress.getByName(http.getRemoteAddr()).isLoopbackAddress()) {
                throw new NotFoundException();
            }
        } catch (UnknownHostException ex) {
            throw new NotFoundException();
        }
        Instant now = clock.instant();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Pass(now, sessions.expire(now)));
    }
}
