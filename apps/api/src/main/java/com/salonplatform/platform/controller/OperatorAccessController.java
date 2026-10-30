package com.salonplatform.platform.controller;

import com.salonplatform.platform.adapter.OperatorAddressAssertion;
import com.salonplatform.platform.dto.OperatorChallengeRequest;
import com.salonplatform.platform.dto.OperatorEnrollment;
import com.salonplatform.platform.dto.OperatorPrincipal;
import com.salonplatform.platform.dto.OperatorRefreshRequest;
import com.salonplatform.platform.dto.OperatorSignIn;
import com.salonplatform.platform.dto.OperatorSignInRequest;
import com.salonplatform.platform.dto.OperatorTokens;
import com.salonplatform.platform.dto.OperatorVerifyRequest;
import com.salonplatform.platform.service.OperatorSessionService;
import com.salonplatform.platform.service.OperatorSignInService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
@Tag(name = "platform")
@RequestMapping(path = "/api/v1/operator", produces = MediaType.APPLICATION_JSON_VALUE)
class OperatorAccessController {

    private final OperatorSignInService signIns;
    private final OperatorSessionService sessions;
    private final OperatorAddressAssertion assertions;

    OperatorAccessController(
            OperatorSignInService signIns, OperatorSessionService sessions, OperatorAddressAssertion assertions) {
        this.signIns = signIns;
        this.sessions = sessions;
        this.assertions = assertions;
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @Parameter(
            name = OperatorAddressAssertion.HEADER,
            in = ParameterIn.HEADER,
            required = false,
            description = "Private server-authenticated rate-address assertion of the console's BFF; absent uses the "
                    + "servlet peer, an invalid proof is refused before rate accounting.",
            schema = @Schema(type = "string", maxLength = 192))
    @PostMapping(path = "/sign-in", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OperatorSignIn> operatorSignIn(
            @Valid @RequestBody OperatorSignInRequest request, HttpServletRequest http) {
        String address = assertions.rateAddress(http);
        return ok(signIns.signIn(request.email(), request.password(), request.deviceLabel(), address));
    }

    @PostMapping(path = "/mfa/challenges", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OperatorEnrollment> operatorEnroll(@Valid @RequestBody OperatorChallengeRequest request) {
        return ok(signIns.enroll(request.challengeId(), request.challengeToken()));
    }

    @PostMapping(path = "/mfa/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OperatorTokens> operatorVerify(@Valid @RequestBody OperatorVerifyRequest request) {
        return ok(signIns.verify(request.challengeId(), request.challengeToken(), request.code()));
    }

    @PostMapping(path = "/sessions/refresh", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OperatorTokens> operatorRefresh(@Valid @RequestBody OperatorRefreshRequest request) {
        return ok(sessions.refresh(request.refreshToken()));
    }

    @PostMapping("/sessions/sign-out")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    ResponseEntity<Void> operatorSignOut() {
        sessions.signOut();
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/me")
    ResponseEntity<OperatorPrincipal> operatorMe() {
        return ok(sessions.me());
    }
}
