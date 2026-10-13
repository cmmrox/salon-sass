package com.salonplatform.identity.api;

import com.salonplatform.identity.IdentityFacade.Principal;
import com.salonplatform.identity.application.StaffAccess;
import com.salonplatform.shared.api.NotFoundException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Validates requests and delegates one use case. Auth responses are never cached. */
@RestController
@RequestMapping(path = "/api/v1/identity", produces = MediaType.APPLICATION_JSON_VALUE)
class StaffAccessController {
    private final StaffAccess access;
    private final com.salonplatform.identity.application.SessionAccess sessions;

    StaffAccessController(
            StaffAccess access, com.salonplatform.identity.application.SessionAccess sessions) {
        this.sessions = sessions;
        this.access = access;
    }

    record SignInRequest(
            @NotBlank @Size(max = 254) String identifier,
            @NotBlank @Size(max = 512) String password,
            @NotBlank @Size(max = 160) String deviceLabel) {}

    record ChallengeRequest(
            @NotNull UUID challengeId, @NotBlank @Size(max = 256) String challengeToken) {}

    record VerifyRequest(
            @NotNull UUID challengeId,
            @NotBlank @Size(max = 256) String challengeToken,
            @NotBlank @Size(max = 32) String code) {}

    record RefreshRequest(@NotBlank @Size(max = 256) String refreshToken) {}

    record PasswordRequest(
            @NotBlank @Size(max = 512) String oldPassword,
            @NotBlank @Size(max = 512) String newPassword) {}

    record CreateStaffRequest(
            @Size(max = 254) String email,
            @Size(max = 32) String mobile,
            @NotBlank @Size(max = 512) String password,
            @NotBlank String role,
            Boolean professionalGrant) {}

    record ChangeStaffRequest(
            @NotNull @Min(0) Long version, String role, Boolean professionalGrant) {}

    private <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(body);
    }

    @PostMapping(path = "/staff/sign-in", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<StaffAccess.SignIn> signIn(
            @Valid @RequestBody SignInRequest request, HttpServletRequest http) {
        return ok(
                access.signIn(
                        request.identifier(),
                        request.password(),
                        request.deviceLabel(),
                        http.getRemoteAddr()));
    }

    @PostMapping("/staff/mfa/challenges")
    ResponseEntity<StaffAccess.SignIn> voluntary() {
        return ok(access.voluntaryMfa());
    }

    @PostMapping(path = "/staff/mfa/enroll", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<StaffAccess.Enrollment> enroll(@Valid @RequestBody ChallengeRequest request) {
        return ok(access.enroll(request.challengeId(), request.challengeToken()));
    }

    @PostMapping(path = "/staff/mfa/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<StaffAccess.Tokens> verify(@Valid @RequestBody VerifyRequest request) {
        return ok(
                access.verifyMfa(request.challengeId(), request.challengeToken(), request.code()));
    }

    @GetMapping("/me")
    ResponseEntity<Principal> me() {
        return ok(sessions.me());
    }

    @GetMapping("/sessions")
    ResponseEntity<StaffAccess.Page<StaffAccess.SessionView>> sessions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ok(sessions.sessions(page, size));
    }

    @PostMapping(path = "/sessions/refresh", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<StaffAccess.Tokens> refresh(@Valid @RequestBody RefreshRequest request) {
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
    ResponseEntity<StaffAccess.Tokens> password(@Valid @RequestBody PasswordRequest request) {
        return ok(access.password(request.oldPassword(), request.newPassword()));
    }

    @GetMapping("/staff")
    ResponseEntity<StaffAccess.Page<StaffAccess.StaffView>> staff(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ok(access.staff(page, size));
    }

    @PostMapping(path = "/staff", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    ResponseEntity<StaffAccess.StaffView> create(@Valid @RequestBody CreateStaffRequest request) {
        return ResponseEntity.status(201)
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(
                        access.create(
                                new StaffAccess.StaffInput(
                                        request.email(),
                                        request.mobile(),
                                        request.password(),
                                        request.role(),
                                        request.professionalGrant())));
    }

    @PatchMapping(path = "/staff/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<StaffAccess.StaffView> change(
            @PathVariable String id, @Valid @RequestBody ChangeStaffRequest request) {
        return ok(
                access.change(
                        id(id),
                        new StaffAccess.StaffChange(
                                request.version(), request.role(), request.professionalGrant())));
    }

    @DeleteMapping("/staff/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    ResponseEntity<Void> remove(@PathVariable String id) {
        access.remove(id(id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/audit")
    ResponseEntity<StaffAccess.Page<StaffAccess.AuditView>> audit(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @Size(max = 128) String subjectId) {
        return ok(access.audits(page, size, subjectId));
    }

    private static UUID id(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            throw new NotFoundException();
        }
    }
}
