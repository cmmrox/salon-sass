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
import com.salonplatform.shared.attempts.AddressAttempts;
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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class StaffSignInServiceImpl implements StaffSignInService {

    private static final Duration RATE_WINDOW = Duration.ofMinutes(15);

    private static final int ATTEMPTS_PER_IDENTIFIER = 10;

    private static final int ATTEMPTS_PER_ADDRESS = 30;

    /** What the address's attempts across every salon are counted for ({@link AddressAttempts}). */
    private static final String SIGN_IN = "staff-sign-in";

    private final IdentityWork work;
    private final StaffAccountRepository accounts;
    private final RateBucketRepository rateBuckets;
    private final StaffMfaChallenges mfaChallenges;
    private final SessionAccess sessions;
    private final StaffAuthority authority;
    private final IdentityCrypto crypto;
    private final PasswordChecks passwords;
    private final Clock clock;
    private final AuditTrail audit;
    private final AddressAttempts addressAttempts;
    private final SignInLimits limits;

    /** The account a password is checked against, as it was read before the check. */
    private record Checked(UUID id, String passwordHash) {}

    /**
     * In three short identity transactions, with the password check (PBKDF2, deliberately slow) between them and
     * inside none, as the operator sign-in does (decision 120, BUG-F02-016): the attempt is counted and committed
     * first, so every attempt counts even if what follows fails; then the account is read; then the outcome is
     * recorded against the account as it is then. Checking inside the transaction held a pooled connection and the
     * salon's identity lock for the whole check, so a few sign-ins in flight stalled every salon on the instance.
     *
     * <p>The check is reserved before anything is counted, so a sign-in refused 503 because the checks were at their
     * limit counts toward none of the limits. Besides the salon's own limits per identifier and per address, the
     * address's attempts are counted across every salon ({@link AddressAttempts}), so that one address cannot try
     * {@value #ATTEMPTS_PER_ADDRESS} times on each of many salons (decision 120).
     */
    @Override
    public SignIn signIn(String identifier, String password, String deviceLabel, String address) {
        String key = normalized(identifier);
        Optional<Checked> checked;
        boolean correct;
        try (PasswordChecks.Reserved check = passwords.reserve()) {
            work.run(() -> {
                boolean identifierAllowed = attempt("staff-identifier", key, ATTEMPTS_PER_IDENTIFIER);
                boolean addressAllowed = attempt("staff-address", address, ATTEMPTS_PER_ADDRESS);
                boolean allowedAcrossSalons = addressAttempts.count(
                                SIGN_IN, crypto.hash("rate-staff-address-across-salons", address), RATE_WINDOW)
                        <= limits.addressAcrossSalons();
                if (!identifierAllowed || !addressAllowed || !allowedAcrossSalons) {
                    record(Actor.system("sign-in"), "sign_in_limited", crypto.hash("audit", key));
                    throw new ApplicationProblemException(429, ErrorCode.RATE_LIMITED);
                }
                return null;
            });
            checked = work.read(() -> accounts.findByIdentifier(key).stream()
                    .findFirst()
                    .map(found -> new Checked(found.getId(), found.getPasswordHash())));
            correct = check.matches(password, checked.map(Checked::passwordHash).orElse(null));
        }
        return work.run(() -> {
            // The account as it is now: one removed, or whose password changed, while its password was being
            // checked is refused like any other wrong credential.
            Optional<StaffAccount> now = checked.flatMap(found -> accounts.findById(found.id()));
            if (now.isEmpty()
                    || !now.get().isActive()
                    || !Objects.equals(
                            now.get().getPasswordHash(), checked.get().passwordHash())
                    || !correct) {
                record(
                        Actor.system("sign-in"),
                        "sign_in_failed",
                        now.map(found -> found.getId().toString()).orElseGet(() -> crypto.hash("audit", key)));
                throw IdentityProblems.invalidCredentials();
            }
            StaffAccount account = now.get();
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

    /**
     * The old password is checked, and the new one hashed, between two identity transactions and inside neither
     * (decision 120). The second refuses the change, as a wrong password, when the password changed meanwhile.
     */
    @Override
    public Tokens changePassword(String oldPassword, String newPassword) {
        Checked checked = work.run(() -> {
            StaffAccount account = authority.permitted("password.change").account();
            return new Checked(account.getId(), account.getPasswordHash());
        });
        String replacement = null;
        if (passwords.matches(oldPassword, checked.passwordHash())) {
            StaffValidation.requireAcceptablePassword(newPassword, "newPassword");
            replacement = passwords.encode(newPassword);
        }
        String newHash = replacement;
        return work.run(() -> {
            StaffAuthority.SignedIn current = authority.permitted("password.change");
            StaffAccount account = current.account();
            if (newHash == null
                    || !account.getId().equals(checked.id())
                    || !Objects.equals(account.getPasswordHash(), checked.passwordHash())) {
                record(staff(account), "password_failed", account.getId().toString());
                throw IdentityProblems.invalidCredentials();
            }
            account.changePassword(newHash, clock.instant());
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
