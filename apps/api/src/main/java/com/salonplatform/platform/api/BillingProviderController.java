package com.salonplatform.platform.api;

import java.util.Locale;

import com.salonplatform.platform.application.ProviderEvents;
import com.salonplatform.platform.infrastructure.BillingProviderKey;
import com.salonplatform.shared.api.NotFoundException;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The billing provider's signed event intake (F03-S02): on the bare platform host only (any other host
 * is 404 before anything is read), with no operator or staff session. The signature covers the exact
 * request body.
 */
@RestController
@RequestMapping(path = "/api/v1/billing", produces = MediaType.APPLICATION_JSON_VALUE)
class BillingProviderController {

    private final ProviderEvents events;
    private final String platformDomain;

    BillingProviderController(ProviderEvents events, @Value("${salon.platform-domain}") String platformDomain) {
        this.events = events;
        this.platformDomain = platformDomain.strip().toLowerCase(Locale.ROOT);
    }

    @PostMapping(path = "/provider-events", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ProviderEvents.Acknowledged> receive(
            @Parameter(name = BillingProviderKey.HEADER, in = ParameterIn.HEADER, required = true,
                    schema = @Schema(type = "string", pattern = "^[0-9a-f]{64}$"))
            @RequestHeader(name = BillingProviderKey.HEADER, required = false) String signature,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @io.swagger.v3.oas.annotations.media.Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "object", requiredProperties = {"eventId", "invoiceId", "attemptKey",
                        "outcome", "occurredAt"})))
            @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        if (!platformHost(request.getHeader(HttpHeaders.HOST))) {
            throw new NotFoundException();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(events.receive(body == null ? new byte[0] : body, signature));
    }

    /** Exactly the platform domain, any letter case, with no port or one valid decimal port. */
    private boolean platformHost(String host) {
        if (host == null) {
            return false;
        }
        String value = host.strip().toLowerCase(Locale.ROOT);
        int colon = value.lastIndexOf(':');
        String name = colon < 0 ? value : value.substring(0, colon);
        if (colon >= 0 && !value.substring(colon + 1).matches("[0-9]{1,5}")) {
            return false;
        }
        return name.equals(platformDomain);
    }
}
