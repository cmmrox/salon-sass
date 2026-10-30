package com.salonplatform.identity.controller;

import com.salonplatform.identity.adapter.ClientAddressAssertion;
import com.salonplatform.identity.dto.AuditView;
import com.salonplatform.identity.dto.ChallengeRequest;
import com.salonplatform.identity.dto.ChangeStaffRequest;
import com.salonplatform.identity.dto.CreateStaffRequest;
import com.salonplatform.identity.dto.Enrollment;
import com.salonplatform.identity.dto.Page;
import com.salonplatform.identity.dto.PasswordRequest;
import com.salonplatform.identity.dto.PrincipalResponse;
import com.salonplatform.identity.dto.RefreshRequest;
import com.salonplatform.identity.dto.SessionView;
import com.salonplatform.identity.dto.SignIn;
import com.salonplatform.identity.dto.SignInRequest;
import com.salonplatform.identity.dto.StaffView;
import com.salonplatform.identity.dto.Tokens;
import com.salonplatform.identity.dto.VerifyRequest;
import com.salonplatform.identity.mapper.PrincipalResponseMapper;
import com.salonplatform.identity.service.SessionService;
import com.salonplatform.identity.service.StaffAccountService;
import com.salonplatform.identity.service.StaffAuditService;
import com.salonplatform.identity.service.StaffMfaService;
import com.salonplatform.identity.service.StaffSignInService;
import com.salonplatform.shared.web.NotFoundException;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Validates requests and delegates one use case. Auth responses are never cached. */
@RestController
@Tag(name = "identity")
@RequestMapping(path = "/api/v1/identity", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
class StaffAccessController {
    private final StaffSignInService signIn;
    private final StaffMfaService mfa;
    private final StaffAccountService accounts;
    private final StaffAuditService audit;
    private final SessionService sessions;
    private final ClientAddressAssertion assertions;
    private final PrincipalResponseMapper principals;

    private <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(body);
    }

    @io.swagger.v3.oas.annotations.Parameter(
            name = "x-salon-client-assertion",
            in = io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,
            required = false,
            description = "Private server-authenticated rate-address assertion; absent uses servlet peer,"
                    + " invalid proof is rejected before accounting.",
            schema = @io.swagger.v3.oas.annotations.media.Schema(type = "string", maxLength = 192))
    @PostMapping(path = "/staff/sign-in", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SignIn> signIn(@Valid @RequestBody SignInRequest request, HttpServletRequest http) {
        return ok(signIn.signIn(
                request.identifier(), request.password(), request.deviceLabel(), assertions.rateAddress(http)));
    }

    @PostMapping("/staff/mfa/challenges")
    ResponseEntity<SignIn> voluntary() {
        return ok(mfa.voluntaryEnrollment());
    }

    @PostMapping(path = "/staff/mfa/enroll", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Enrollment> enroll(@Valid @RequestBody ChallengeRequest request) {
        return ok(mfa.enroll(request.challengeId(), request.challengeToken()));
    }

    @PostMapping(path = "/staff/mfa/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Tokens> verify(@Valid @RequestBody VerifyRequest request) {
        return ok(mfa.verify(request.challengeId(), request.challengeToken(), request.code()));
    }

    @GetMapping("/me")
    ResponseEntity<PrincipalResponse> me() {
        return ok(principals.toResponse(sessions.me()));
    }

    @GetMapping("/sessions")
    ResponseEntity<Page<SessionView>> sessions(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ok(sessions.sessions(page, size));
    }

    @PostMapping(path = "/sessions/refresh", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Tokens> refresh(@Valid @RequestBody RefreshRequest request) {
        return ok(sessions.refresh(request.refreshToken()));
    }

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    ResponseEntity<Void> revoke(@PathVariable String id) {
        sessions.revoke(id(id));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/sessions")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    ResponseEntity<Void> revokeAll() {
        sessions.revokeAll();
        return ResponseEntity.noContent().build();
    }

    @PutMapping(path = "/staff/password", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Tokens> password(@Valid @RequestBody PasswordRequest request) {
        return ok(signIn.changePassword(request.oldPassword(), request.newPassword()));
    }

    @GetMapping("/staff")
    ResponseEntity<Page<StaffView>> staff(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ok(accounts.list(page, size));
    }

    @PostMapping(path = "/staff", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    ResponseEntity<StaffView> create(@Valid @RequestBody CreateStaffRequest request) {
        return ResponseEntity.status(201)
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(accounts.create(new StaffAccountService.StaffInput(
                        request.email(),
                        request.mobile(),
                        request.password(),
                        request.role(),
                        request.professionalGrant())));
    }

    @PatchMapping(path = "/staff/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<StaffView> change(@PathVariable String id, @Valid @RequestBody ChangeStaffRequest request) {
        return ok(accounts.change(
                id(id),
                new StaffAccountService.StaffChange(request.version(), request.role(), request.professionalGrant())));
    }

    @DeleteMapping("/staff/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    ResponseEntity<Void> remove(@PathVariable String id) {
        accounts.remove(id(id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/audit")
    ResponseEntity<Page<AuditView>> audit(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @Size(max = 128) String subjectId) {
        return ok(audit.list(page, size, subjectId));
    }

    private static UUID id(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            throw new NotFoundException();
        }
    }
}
