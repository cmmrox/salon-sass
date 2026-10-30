package com.salonplatform.identity.service.impl;

import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.identity.config.CustomerCodeSettings;
import com.salonplatform.identity.domain.Identifiers;
import com.salonplatform.identity.dto.Challenge;
import com.salonplatform.identity.dto.Tokens;
import com.salonplatform.identity.entity.CustomerAccount;
import com.salonplatform.identity.entity.CustomerChallenge;
import com.salonplatform.identity.entity.RateBucket;
import com.salonplatform.identity.repository.CustomerAccountRepository;
import com.salonplatform.identity.repository.CustomerChallengeRepository;
import com.salonplatform.identity.repository.ExpiredSecurityState;
import com.salonplatform.identity.repository.RateBucketRepository;
import com.salonplatform.identity.repository.SyntheticIdentityReset;
import com.salonplatform.identity.service.CustomerAccessService;
import com.salonplatform.identity.service.IdentityWork;
import com.salonplatform.identity.service.SessionService;
import com.salonplatform.shared.audit.Actor;
import com.salonplatform.shared.audit.ActorKind;
import com.salonplatform.shared.audit.AuditAction;
import com.salonplatform.shared.audit.AuditTrail;
import com.salonplatform.shared.outbox.Outbox;
import com.salonplatform.shared.outbox.OutboxDelivery;
import com.salonplatform.shared.outbox.OutboxMessageView;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.NotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
class CustomerAccessServiceImpl implements CustomerAccessService {

    private static final Duration CODE_WINDOW = Duration.ofMinutes(15);

    private final IdentityWork work;
    private final SessionService sessions;
    private final CustomerChallengeRepository challenges;
    private final CustomerAccountRepository customers;
    private final RateBucketRepository rateBuckets;
    private final ExpiredSecurityState expired;
    private final SyntheticIdentityReset syntheticReset;
    private final IdentityCrypto crypto;
    private final Clock clock;
    private final CustomerCodeSettings settings;
    private final AuditTrail audit;
    private final Outbox outbox;
    private final MeterRegistry meters;
    private final Environment environment;
    private final JsonMapper json;
    private final SecureRandom random = new SecureRandom();

    private static ApplicationProblemException invalid() {
        return new ApplicationProblemException(401, ErrorCode.INVALID_CODE);
    }

    private String purpose(UUID id) {
        return "customer-code:" + TenantContext.require().id() + ":" + id;
    }

    private void event(Actor actor, String action, String subject) {
        audit.record(new AuditAction(actor, "identity." + action, "customer_challenge", subject, null, null, null));
    }

    @Override
    public Challenge request(String mobile, String channel, String label, String deviceId, String address) {
        TenantContext.require();
        String number;
        try {
            number = Identifiers.mobile(mobile);
        } catch (IllegalArgumentException ex) {
            throw IdentityProblems.invalidField("mobile", "must be a Sri Lankan mobile number");
        }
        if (number == null) {
            throw IdentityProblems.invalidField("mobile", "is required");
        }
        if (!Set.of("sms", "whatsapp").contains(channel == null ? "" : channel)) {
            throw IdentityProblems.invalidField("channel", "must be sms or whatsapp");
        }
        StaffValidation.requireDeviceLabel(label);
        if (deviceId == null) {
            throw IdentityProblems.invalidField("device", "requires a server-issued device");
        }
        if (!environment.matchesProfiles("local")) {
            throw new ApplicationProblemException(503, ErrorCode.PROVIDER_UNAVAILABLE);
        }
        return work.run(() -> {
            String numberHash = crypto.hash("customer-number", number);
            Instant now = clock.instant();
            List<Candidate> buckets = List.of(
                    bucket("customer-number", numberHash, now, false),
                    bucket("customer-device", crypto.hash("customer-device", deviceId), now, false),
                    bucket("customer-address", crypto.hash("customer-address", address), now, false),
                    bucket("customer-day", "salon", now, true));
            int[] ceilings = {
                settings.numberLimit(), settings.deviceLimit(), settings.addressLimit(), settings.dailyLimit()
            };
            long retryAfter = 0;
            for (int i = 0; i < buckets.size(); i++) {
                Candidate candidate = buckets.get(i);
                if (candidate.attempts() >= ceilings[i]) {
                    Duration remaining = Duration.between(now, candidate.start().plusSeconds(i == 3 ? 86400 : 900));
                    long seconds = remaining.getSeconds() + (remaining.getNano() > 0 ? 1 : 0);
                    retryAfter = Math.max(retryAfter, Math.max(1, seconds));
                }
            }
            if (retryAfter > 0) {
                event(Actor.system("code-request"), "code_request_limited", numberHash);
                throw new ApplicationProblemException(429, ErrorCode.RATE_LIMITED).retryAfter(retryAfter);
            }
            for (Candidate candidate : buckets) {
                candidate.row().countAccepted(candidate.start(), candidate.attempts(), now);
                if (!candidate.stored()) {
                    rateBuckets.persist(candidate.row());
                }
            }
            RateBucket daily = buckets.getLast().row();
            int threshold = (int) Math.ceil(settings.dailyLimit() * .8);
            if (daily.getAttempts() == threshold) {
                String day = now.atZone(ZoneOffset.UTC).toLocalDate().toString();
                event(Actor.system("code-volume"), "code_volume_alert", day);
                meters.counter(
                                "salon.identity.code.volume.alerts",
                                "tenant.id",
                                TenantContext.require().id().toString())
                        .increment();
            }
            for (CustomerChallenge previous :
                    challenges.findByNumberHashAndInvalidatedAtIsNullAndConsumedAtIsNull(numberHash)) {
                previous.invalidate(now);
                if (outbox.cancel(previous.getMessageId(), now)) {
                    event(
                            Actor.system("code-request"),
                            "code_cancelled",
                            previous.getId().toString());
                }
            }
            UUID id = UUID.randomUUID();
            String encryptedRecipient = crypto.encrypt(purpose(id) + ":recipient", number);
            String deviceHash = crypto.hash("customer-device", deviceId);
            String code = String.format(Locale.ROOT, "%06d", random.nextInt(1000000));
            String codeHash = crypto.hash(purpose(id), code);
            String encrypted = crypto.encrypt(purpose(id), json.writeValueAsString(new ClearMessage(number, code)));
            UUID messageId = outbox.enqueue(channel, new Envelope(id, encrypted), now);
            CustomerChallenge challenge = challenges.persist(CustomerChallenge.issue(
                    id,
                    new CustomerChallenge.Delivery(
                            numberHash, encryptedRecipient, codeHash, deviceHash, label, channel, messageId),
                    now));
            event(Actor.system("code-request"), "code_requested", id.toString());
            return new Challenge(id, challenge.getExpiresAt());
        });
    }

    /**
     * One dimension's bucket and the count a request would be measured against, computed without writing:
     * denied requests persist only their security audit.
     *
     * @param stored whether the bucket's row already exists
     */
    private record Candidate(RateBucket row, boolean stored, Instant start, int attempts) {}

    private Candidate bucket(String dimension, String hash, Instant now, boolean daily) {
        Instant start = daily ? now.truncatedTo(ChronoUnit.DAYS) : now;
        Optional<RateBucket> stored = rateBuckets.findFirstByDimensionAndKeyHash(dimension, hash);
        if (stored.isEmpty()) {
            return new Candidate(RateBucket.open(dimension, hash, now), false, start, 0);
        }
        RateBucket row = stored.get();
        boolean expired = daily
                ? !row.getWindowStart().equals(start)
                : !now.isBefore(row.getWindowStart().plus(CODE_WINDOW));
        return new Candidate(row, true, expired ? start : row.getWindowStart(), expired ? 0 : row.getAttempts());
    }

    @Override
    public Tokens verify(UUID id, String code, String deviceId) {
        return work.run(() -> {
            CustomerChallenge challenge = challenges.findById(id).orElseThrow(NotFoundException::new);
            if (challenge.isSpent()) {
                event(
                        Actor.system("code-sign-in"),
                        "customer_sign_in_failed",
                        challenge.getId().toString());
                throw invalid();
            }
            if (challenge.isExpiredAt(clock.instant())) {
                event(
                        Actor.system("code-sign-in"),
                        "customer_sign_in_expired",
                        challenge.getId().toString());
                throw new ApplicationProblemException(401, ErrorCode.CODE_EXPIRED);
            }
            if (deviceId == null
                    || !crypto.equalHash(challenge.getDeviceHash(), crypto.hash("customer-device", deviceId))) {
                event(
                        Actor.system("code-sign-in"),
                        "customer_sign_in_failed",
                        challenge.getId().toString());
                throw invalid();
            }
            challenge.recordAttempt(clock.instant());
            if (code == null
                    || !code.matches("[0-9]{6}")
                    || !crypto.equalHash(challenge.getCodeHash(), crypto.hash(purpose(challenge.getId()), code))) {
                event(
                        Actor.system("code-sign-in"),
                        "customer_sign_in_failed",
                        challenge.getId().toString());
                throw invalid();
            }
            String mobile =
                    crypto.decrypt(purpose(challenge.getId()) + ":recipient", challenge.getEncryptedRecipient());
            CustomerAccount account = customers
                    .findFirstByMobile(mobile)
                    .orElseGet(() -> customers.persist(CustomerAccount.verified(mobile, clock.instant())));
            if (!account.isActive()) {
                throw invalid();
            }
            challenge.consume(clock.instant());
            if (outbox.cancel(challenge.getMessageId(), clock.instant())) {
                event(
                        Actor.system("code-sign-in"),
                        "code_cancelled",
                        challenge.getId().toString());
            }
            Tokens credentials = sessions.openCustomerSession(account.getId(), challenge.getDeviceLabel());
            audit.record(new AuditAction(
                    new Actor(ActorKind.CUSTOMER, account.getId().toString()),
                    "identity.sign_in",
                    "session",
                    credentials.sessionId().toString(),
                    null,
                    null,
                    null));
            return credentials;
        });
    }

    @Override
    public boolean captureIfLive(OutboxDelivery delivery, Consumer<ClearMessage> capture) {
        Envelope payload = json.readValue(delivery.payload(), Envelope.class);
        return work.run(() -> {
            CustomerChallenge challenge =
                    challenges.findById(payload.challengeId()).orElse(null);
            if (challenge == null
                    || !challenge.getMessageId().equals(delivery.messageId())
                    || !challenge.getChannel().equals(delivery.channel())
                    || challenge.getInvalidatedAt() != null
                    || challenge.getConsumedAt() != null
                    || challenge.isExpiredAt(clock.instant())) {
                if (outbox.cancel(delivery.messageId(), clock.instant())) {
                    event(
                            Actor.system("code-delivery"),
                            "code_cancelled",
                            payload.challengeId().toString());
                }
                return false;
            }
            ClearMessage clear =
                    json.readValue(crypto.decrypt(purpose(challenge.getId()), payload.encrypted()), ClearMessage.class);
            if (!crypto.equalHash(challenge.getCodeHash(), crypto.hash(purpose(challenge.getId()), clear.code()))
                    || !crypto.equalHash(challenge.getNumberHash(), crypto.hash("customer-number", clear.mobile()))) {
                throw new IllegalStateException("Invalid encrypted message envelope");
            }
            capture.accept(clear);
            return true;
        });
    }

    @Override
    public Optional<UUID> messageFor(UUID challenge) {
        return work.run(() -> challenges.findById(challenge).map(CustomerChallenge::getMessageId));
    }

    @Override
    public OutboxMessageView delivery(UUID challenge) {
        return work.run(() -> {
            UUID message = challenges
                    .findById(challenge)
                    .map(CustomerChallenge::getMessageId)
                    .orElseThrow(NotFoundException::new);
            return outbox.find(message).orElseThrow(NotFoundException::new);
        });
    }

    @Override
    public void resetCustomer() {
        work.run(() -> {
            SyntheticSalons.requireCurrentIsSynthetic();
            for (CustomerChallenge challenge : challenges.findAll()) {
                outbox.cancel(challenge.getMessageId(), clock.instant());
            }
            syntheticReset.removeCustomerRecords();
            return null;
        });
    }

    @Override
    public void cleanup(Instant now) {
        for (CustomerChallenge challenge : challenges.findByExpiresAtLessThanEqual(now)) {
            outbox.cancel(challenge.getMessageId(), now);
        }
        expired.removeCustomerChallengesExpiredBy(now);
        expired.removeCustomerRateBucketsStartedBy(now.minus(CODE_WINDOW));
    }
}
