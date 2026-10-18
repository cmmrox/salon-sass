package com.salonplatform.platform.api;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.salonplatform.platform.application.OperatorAccess;
import com.salonplatform.platform.infrastructure.OperatorAddressAssertion;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator sign-in, MFA, renewal, sign-out and the current principal, on the console hostname only
 * ({@code OperatorHostFilter}). Every answer is {@code no-store}.
 */
@RestController
@RequestMapping(path = "/api/v1/operator", produces = MediaType.APPLICATION_JSON_VALUE)
class OperatorAccessController {

    private final OperatorAccess access;
    private final OperatorAddressAssertion assertions;

    OperatorAccessController(OperatorAccess access, OperatorAddressAssertion assertions) {
        this.access = access;
        this.assertions = assertions;
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record OperatorSignInRequest(@NotBlank @Size(max = 320) String email, @NotBlank @Size(max = 512) String password,
            @NotBlank @Size(max = 160) String deviceLabel) {
        @JsonAnySetter
        void unknown(String field, Object ignored) {
            throw OperatorRequests.unexpected(field);
        }
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record OperatorChallengeRequest(@NotNull UUID challengeId, @NotBlank @Size(max = 256) String challengeToken) {
        @JsonAnySetter
        void unknown(String field, Object ignored) {
            throw OperatorRequests.unexpected(field);
        }
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record OperatorVerifyRequest(@NotNull UUID challengeId, @NotBlank @Size(max = 256) String challengeToken,
            @NotBlank @Size(max = 32) String code) {
        @JsonAnySetter
        void unknown(String field, Object ignored) {
            throw OperatorRequests.unexpected(field);
        }
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record OperatorRefreshRequest(@NotBlank @Size(max = 256) String refreshToken) {
        @JsonAnySetter
        void unknown(String field, Object ignored) {
            throw OperatorRequests.unexpected(field);
        }
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @Parameter(name = OperatorAddressAssertion.HEADER, in = ParameterIn.HEADER, required = false,
            description = "Private server-authenticated rate-address assertion of the console's BFF; absent uses the "
                    + "servlet peer, an invalid proof is refused before rate accounting.",
            schema = @Schema(type = "string", maxLength = 192))
    @PostMapping(path = "/sign-in", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OperatorAccess.OperatorSignIn> operatorSignIn(@Valid @RequestBody OperatorSignInRequest request,
            HttpServletRequest http) {
        String address = assertions.rateAddress(http);
        return ok(access.signIn(request.email(), request.password(), request.deviceLabel(), address));
    }

    @PostMapping(path = "/mfa/challenges", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OperatorAccess.OperatorEnrollment> operatorEnroll(@Valid @RequestBody OperatorChallengeRequest request) {
        return ok(access.enroll(request.challengeId(), request.challengeToken()));
    }

    @PostMapping(path = "/mfa/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OperatorAccess.OperatorTokens> operatorVerify(@Valid @RequestBody OperatorVerifyRequest request) {
        return ok(access.verify(request.challengeId(), request.challengeToken(), request.code()));
    }

    @PostMapping(path = "/sessions/refresh", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OperatorAccess.OperatorTokens> operatorRefresh(@Valid @RequestBody OperatorRefreshRequest request) {
        return ok(access.refresh(request.refreshToken()));
    }

    @PostMapping("/sessions/sign-out")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    ResponseEntity<Void> operatorSignOut() {
        access.signOut();
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/me")
    ResponseEntity<OperatorAccess.OperatorPrincipal> operatorMe() {
        return ok(access.me());
    }
}
