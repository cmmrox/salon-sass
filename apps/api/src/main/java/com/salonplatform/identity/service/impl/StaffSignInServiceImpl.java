package com.salonplatform.identity.service.impl;

import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.identity.domain.Identifiers;
import com.salonplatform.identity.domain.MfaPurpose;
import com.salonplatform.identity.domain.SessionKind;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.identity.dto.SignIn;
import com.salonplatform.identity.dto.Tokens;
import com.salonplatform.identity.entity.RateBucket;
import com.salonplatform.identity.entity.StaffAccount;
import com.salonplatform.identity.repository.RateBucketRepository;
import com.salonplatform.identity.repository.StaffAccountRepository;
import com.salonplatform.identity.service.IdentityWork;
import com.salonplatform.identity.service.StaffSignInService;
import com.salonplatform.shared.audit.Actor;
import com.salonplatform.shared.audit.ActorKind;
import com.salonplatform.shared.audit.AuditAction;
import com.salonplatform.shared.audit.AuditTrail;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class StaffSignInServiceImpl implements StaffSignInService {

    private static final Duration RATE_WINDOW = Duration.ofMinutes(15);

    private static final int ATTEMPTS_PER_IDENTIFIER = 10;

    private static final int ATTEMPTS_PER_ADDRESS = 30;

    private final IdentityWork work;
    private final StaffAccountRepository accounts;
    private final RateBucketRepository rateBuckets;
    private final StaffMfaChallenges mfaChallenges;
    private final SessionAccess sessions;
    private final StaffAuthority authority;
    private final IdentityCrypto crypto;
    private final Clock clock;
    private final AuditTrail audit;

    @Override
    public SignIn signIn(String identifier, String password, String deviceLabel, String address) {
        return work.run(() -> {
            String key = normalized(identifier);
            boolean identifierAllowed = attempt("staff-identifier", key, ATTEMPTS_PER_IDENTIFIER);
            boolean addressAllowed = attempt("staff-address", address, ATTEMPTS_PER_ADDRESS);
            if (!identifierAllowed || !addressAllowed) {
                record(Actor.system("sign-in"), "sign_in_limited", crypto.hash("audit", key));
                throw new ApplicationProblemException(429, ErrorCode.RATE_LIMITED);
            }
            StaffAccount account =
                    accounts.findByIdentifier(key).stream().findFirst().orElse(null);
            boolean correct = crypto.matchesPassword(password, account == null ? null : account.getPasswordHash());
            if (account == null || !account.isActive() || !correct) {
                record(
                        Actor.system("sign-in"),
                        "sign_in_failed",
                        account == null
                                ? crypto.hash("audit", key)
                                : account.getId().toString());
                throw IdentityProblems.invalidCredentials();
            }
            StaffValidation.requireDeviceLabel(deviceLabel);
            if (account.hasMfa() || account.getRole() == StaffRole.OWNER) {
                return mfaChallenges.issue(
                        account, null, account.hasMfa() ? MfaPurpose.VERIFY : MfaPurpose.ENROLL, deviceLabel);
            }
            Tokens tokens = sessions.newStaff(account, deviceLabel, false);
            audit.record(new AuditAction(
                    staff(account),
                    "identity.sign_in",
                    "session",
                    tokens.sessionId().toString(),
                    null,
                    null,
                    null));
            return new SignIn("authenticated", null, null, null, tokens);
        });
    }

    @Override
    public Tokens changePassword(String oldPassword, String newPassword) {
        return work.run(() -> {
            StaffAuthority.SignedIn current = authority.permitted("password.change");
            StaffAccount account = current.account();
            if (!crypto.matchesPassword(oldPassword, account.getPasswordHash())) {
                record(staff(account), "password_failed", account.getId().toString());
                throw IdentityProblems.invalidCredentials();
            }
            StaffValidation.requireAcceptablePassword(newPassword, "newPassword");
            account.changePassword(crypto.encodePassword(newPassword), clock.instant());
            sessions.revokeOwner(
                    SessionKind.STAFF, account.getId(), current.session().getId());
            record(staff(account), "password_changed", account.getId().toString());
            return sessions.rotate(current.session(), true);
        });
    }

    /** The identifier as an account would hold it; one that is neither an email nor a number still counts. */
    private static String normalized(String identifier) {
        try {
            return Identifiers.identifier(identifier);
        } catch (IllegalArgumentException ex) {
            return identifier == null ? "" : identifier.trim().toLowerCase(Locale.ROOT);
        }
    }

    /** @return whether the attempt is within the dimension's ceiling */
    private boolean attempt(String dimension, String key, int ceiling) {
        String hash = crypto.hash("rate-" + dimension, key);
        Instant now = clock.instant();
        RateBucket bucket = rateBuckets
                .findFirstByDimensionAndKeyHash(dimension, hash)
                .orElseGet(() -> rateBuckets.persist(RateBucket.open(dimension, hash, now)));
        return bucket.countAttempt(now, RATE_WINDOW, ceiling);
    }

    private static Actor staff(StaffAccount account) {
        return new Actor(ActorKind.STAFF, account.getId().toString());
    }

    private void record(Actor actor, String action, String subject) {
        audit.record(new AuditAction(actor, "identity." + action, "staff", subject, null, null, null));
    }
}
