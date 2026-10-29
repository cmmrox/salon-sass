package com.salonplatform.identity.service;

import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.identity.domain.MfaPurpose;
import com.salonplatform.identity.domain.SessionKind;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.identity.dto.Enrollment;
import com.salonplatform.identity.dto.SignIn;
import com.salonplatform.identity.dto.Tokens;
import com.salonplatform.identity.entity.AccessSession;
import com.salonplatform.identity.entity.MfaChallenge;
import com.salonplatform.identity.entity.MfaStep;
import com.salonplatform.identity.entity.StaffAccount;
import com.salonplatform.identity.repository.AccessSessionRepository;
import com.salonplatform.identity.repository.MfaChallengeRepository;
import com.salonplatform.identity.repository.MfaStepRepository;
import com.salonplatform.identity.repository.StaffAccountRepository;
import com.salonplatform.shared.audit.Actor;
import com.salonplatform.shared.audit.ActorKind;
import com.salonplatform.shared.audit.AuditAction;
import com.salonplatform.shared.audit.AuditTrail;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.NotFoundException;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * A staff member's second step (docs/architecture/identity-and-access.md): authenticator-app enrollment, offered
 * after a correct password or from a signed-in session, and verification of a code, each through a single-use
 * challenge. A code's time step is accepted once per account.
 */
@Service
@RequiredArgsConstructor
public class StaffMfa {

    private final IdentityWork work;
    private final MfaChallengeRepository challenges;
    private final MfaStepRepository usedSteps;
    private final StaffAccountRepository accounts;
    private final AccessSessionRepository sessionRows;
    private final SessionAccess sessions;
    private final StaffAuthority authority;
    private final IdentityCrypto crypto;
    private final Clock clock;
    private final AuditTrail audit;

    /** A signed-in staff member without a second step asks to enroll one. */
    public SignIn voluntaryEnrollment() {
        return work.run(() -> {
            StaffAuthority.SignedIn current = authority.permitted("mfa.enroll");
            if (current.account().hasMfa()) {
                throw IdentityProblems.conflict();
            }
            return challenge(
                    current.account(),
                    current.session(),
                    MfaPurpose.ENROLL,
                    current.session().getDeviceLabel());
        });
    }

    /**
     * A new challenge, whose token is handed to the caller once and kept only as a hash.
     *
     * @param session the session that asked for it, or null after a password sign-in
     */
    SignIn challenge(StaffAccount account, AccessSession session, String purpose, String deviceLabel) {
        String token = crypto.randomToken();
        MfaChallenge challenge = challenges.persist(MfaChallenge.issue(
                account.getId(),
                session == null ? null : session.getId(),
                purpose,
                deviceLabel,
                crypto.hash("challenge", token),
                clock.instant()));
        return new SignIn(purpose, challenge.getId(), token, challenge.getExpiresAt(), null);
    }

    /** The authenticator secret of an enrollment challenge, shown once with its {@code otpauth} URI. */
    public Enrollment enroll(UUID challengeId, String token) {
        return work.run(() -> {
            MfaChallenge challenge = verified(challengeId, token);
            if (!challenge.isEnrollment()) {
                throw IdentityProblems.invalidCredentials();
            }
            StaffAccount account = account(challenge.getAccountId());
            if (account.hasMfa()) {
                throw IdentityProblems.conflict();
            }
            String purpose = secretPurpose(account);
            String pending = challenge.getPendingSecret() != null
                    ? challenge.getPendingSecret()
                    : crypto.encrypt(purpose, crypto.newTotpSecret());
            challenge.offerSecret(pending, clock.instant());
            String secret = crypto.decrypt(purpose, pending);
            String uri = "otpauth://totp/Salon:"
                    + account.getId()
                    + "?secret="
                    + secret
                    + "&issuer=Salon&algorithm=SHA1&digits=6&period=30";
            return new Enrollment(challenge.getId(), secret, uri, challenge.getExpiresAt());
        });
    }

    /**
     * Verifies a code against the account's secret, or the secret being enrolled, and signs the staff member in.
     * Confirming an enrollment ends the account's other sessions.
     */
    public Tokens verify(UUID challengeId, String token, String code) {
        return work.run(() -> {
            MfaChallenge challenge = verified(challengeId, token);
            StaffAccount account = account(challenge.getAccountId());
            challenge.recordAttempt(clock.instant());
            if (challenge.isEnrollment() && account.hasMfa()) {
                throw IdentityProblems.invalidCredentials();
            }
            String encrypted = challenge.isEnrollment() ? challenge.getPendingSecret() : account.getMfaSecret();
            if (encrypted == null) {
                record(account, "mfa_failed", "challenge", challenge.getId());
                throw IdentityProblems.invalidCredentials();
            }
            String secret = crypto.decrypt(secretPurpose(account), encrypted);
            Long step = crypto.matchingStep(secret, code);
            if (step == null || usedSteps.existsByAccountIdAndStep(account.getId(), step)) {
                record(account, "mfa_failed", "challenge", challenge.getId());
                throw IdentityProblems.invalidCredentials();
            }
            usedSteps.persist(MfaStep.used(account.getId(), step, clock.instant()));
            challenge.consume(clock.instant());
            if (challenge.isEnrollment()) {
                account.enrollMfa(challenge.getPendingSecret(), clock.instant());
                sessions.revokeOwner(SessionKind.STAFF, account.getId(), null);
                record(account, "mfa_enrolled", "staff", account.getId());
            }
            Tokens tokens = sessions.newStaff(account, challenge.getDeviceLabel(), true);
            record(account, "sign_in", "session", tokens.sessionId());
            return tokens;
        });
    }

    /** The challenge the token belongs to, still usable, for an active account and, if it has one, a live session. */
    private MfaChallenge verified(UUID challengeId, String token) {
        MfaChallenge challenge = challenges.findById(challengeId).orElseThrow(IdentityProblems::invalidCredentials);
        if (!crypto.equalHash(challenge.getCredentialHash(), crypto.hash("challenge", token == null ? "" : token))) {
            throw IdentityProblems.invalidCredentials();
        }
        if (!challenge.isUsableAt(clock.instant())) {
            throw IdentityProblems.invalidCredentials();
        }
        StaffAccount account = account(challenge.getAccountId());
        if (!account.isActive()) {
            throw IdentityProblems.invalidCredentials();
        }
        if (challenge.getSessionId() != null
                && !liveStaffSession(
                        account, sessionRows.findById(challenge.getSessionId()).orElseThrow(NotFoundException::new))) {
            throw IdentityProblems.invalidCredentials();
        }
        return challenge;
    }

    private boolean liveStaffSession(StaffAccount account, AccessSession session) {
        return account.isActive()
                && session.getRevokedAt() == null
                && clock.instant().isBefore(session.getExpiresAt())
                && session.isStaff()
                && (account.getRole() != StaffRole.OWNER || (account.hasMfa() && session.isMfaVerified()));
    }

    private StaffAccount account(UUID id) {
        return accounts.findById(id).orElseThrow(NotFoundException::new);
    }

    /** The authenticator secret is encrypted for this salon and account only. */
    private static String secretPurpose(StaffAccount account) {
        return "totp:" + TenantContext.require().id() + ":" + account.getId();
    }

    private void record(StaffAccount actor, String action, String subjectKind, UUID subject) {
        audit.record(new AuditAction(
                new Actor(ActorKind.STAFF, actor.getId().toString()),
                "identity." + action,
                subjectKind,
                subject.toString(),
                null,
                null,
                null));
    }
}
