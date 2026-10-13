package com.salonplatform.identity.api;

import com.salonplatform.identity.application.CustomerAccess;
import com.salonplatform.identity.application.StaffAccess;
import com.salonplatform.identity.infrastructure.CustomerDevice;
import com.salonplatform.shared.tenancy.TenantContext;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Customer authentication is public, salon-selected and bound to an authenticated device cookie.
 */
@RestController
@RequestMapping(path = "/api/v1/identity/customers", produces = MediaType.APPLICATION_JSON_VALUE)
@io.swagger.v3.oas.annotations.security.SecurityScheme(
        name = "customerDevice",
        type = io.swagger.v3.oas.annotations.enums.SecuritySchemeType.APIKEY,
        in = io.swagger.v3.oas.annotations.enums.SecuritySchemeIn.COOKIE,
        paramName = "salon_device")
class CustomerAccessController {
    static final String DEVICE_COOKIE = "salon_device";
    private final CustomerAccess access;
    private final com.salonplatform.identity.infrastructure.ClientAddressAssertion assertions;
    private final CustomerDevice devices;
    private final Environment environment;

    CustomerAccessController(
            CustomerAccess access,
            CustomerDevice devices,
            Environment environment,
            com.salonplatform.identity.infrastructure.ClientAddressAssertion assertions) {
        this.access = access;
        this.assertions = assertions;
        this.devices = devices;
        this.environment = environment;
    }

    record CodeRequest(
            @NotBlank @Size(max = 32) String mobile,
            @NotBlank @Size(max = 16) String channel,
            @NotBlank @Size(max = 160) String deviceLabel) {}

    record CustomerCodeVerificationRequest(
            @NotNull UUID challengeId, @NotBlank @Size(max = 32) String code) {}

    @io.swagger.v3.oas.annotations.Parameter(
            name = "x-salon-client-assertion",
            in = io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,
            required = false,
            description =
                    "Private server-authenticated rate-address assertion; absent uses servlet peer,"
                        + " invalid proof is rejected before accounting.",
            schema = @io.swagger.v3.oas.annotations.media.Schema(type = "string", maxLength = 192))
    @PostMapping(path = "/challenges", consumes = MediaType.APPLICATION_JSON_VALUE)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Code request accepted",
                content =
                        @io.swagger.v3.oas.annotations.media.Content(
                                mediaType = "application/json",
                                schema =
                                        @io.swagger.v3.oas.annotations.media.Schema(
                                                implementation = CustomerAccess.Challenge.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "An accepted-send ceiling is reached",
                headers =
                        @io.swagger.v3.oas.annotations.headers.Header(
                                name = "Retry-After",
                                description =
                                        "Positive seconds until the longest blocking window"
                                                + " expires",
                                schema =
                                        @io.swagger.v3.oas.annotations.media.Schema(
                                                type = "integer",
                                                minimum = "1")),
                content =
                        @io.swagger.v3.oas.annotations.media.Content(
                                mediaType = "application/problem+json",
                                schema =
                                        @io.swagger.v3.oas.annotations.media.Schema(
                                                ref = "#/components/schemas/Problem")))
    })
    ResponseEntity<CustomerAccess.Challenge> request(
            @Valid @RequestBody CodeRequest request, HttpServletRequest http) {
        TenantContext.require();
        String rateAddress = assertions.rateAddress(http);
        String cookie = cookie(http);
        String id = devices.verifiedId(cookie);
        boolean issued = id == null;
        if (issued) {
            cookie = devices.issue();
            id = devices.verifiedId(cookie);
        }
        CustomerAccess.Challenge challenge =
                access.request(
                        request.mobile(),
                        request.channel(),
                        request.deviceLabel(),
                        id,
                        rateAddress);
        ResponseEntity.BodyBuilder response =
                ResponseEntity.ok().cacheControl(CacheControl.noStore());
        if (issued)
            response.header(
                    HttpHeaders.SET_COOKIE,
                    ResponseCookie.from(DEVICE_COOKIE, cookie)
                            .httpOnly(true)
                            .secure(!environment.matchesProfiles("local"))
                            .sameSite("Lax")
                            .path("/")
                            .maxAge(java.time.Duration.ofDays(365))
                            .build()
                            .toString());
        return response.body(challenge);
    }

    @PostMapping(path = "/sign-in", consumes = MediaType.APPLICATION_JSON_VALUE)
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "customerDevice")
    ResponseEntity<StaffAccess.Tokens> verify(
            @Valid @RequestBody CustomerCodeVerificationRequest request, HttpServletRequest http) {
        TenantContext.require();
        String device = devices.verifiedId(cookie(http));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(access.verify(request.challengeId(), request.code(), device));
    }

    private static String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        String value = null;
        for (Cookie c : request.getCookies())
            if (c.getName().equals(DEVICE_COOKIE)) {
                if (value != null) return null;
                value = c.getValue();
            }
        return value;
    }
}
