package com.salonplatform.identity.application;

import com.salonplatform.identity.domain.*;
import com.salonplatform.identity.infrastructure.*;
import com.salonplatform.shared.api.*;
import com.salonplatform.shared.audit.*;
import com.salonplatform.shared.outbox.*;
import com.salonplatform.shared.tenancy.*;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.*;
import java.security.SecureRandom;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Salon-scoped, one-use customer codes with atomic accepted-send ceilings and encrypted delivery.
 */
@Service
public class CustomerAccess {
    @PersistenceContext
    private EntityManager em;

    private final IdentityWork work;
    private final SessionAccess sessions;
    private final IdentityCrypto crypto;
    private final Clock clock;
    private final CustomerCodeSettings settings;
    private final AuditTrail audit;
    private final Outbox outbox;
    private final MeterRegistry meters;
    private final Environment environment;
    private final JsonMapper json;
    private final SecureRandom random = new SecureRandom();

    public CustomerAccess(
            IdentityWork work,
            SessionAccess sessions,
            IdentityCrypto crypto,
            Clock clock,
            CustomerCodeSettings settings,
            AuditTrail audit,
            Outbox outbox,
            MeterRegistry meters,
            Environment environment,
            JsonMapper json) {
        this.work = work;
        this.sessions = sessions;
        this.crypto = crypto;
        this.clock = clock;
        this.settings = settings;
        this.audit = audit;
        this.outbox = outbox;
        this.meters = meters;
        this.environment = environment;
        this.json = json;
    }

    @io.swagger.v3.oas.annotations.media.Schema(requiredProperties = {"challengeId", "expiresAt"})
    public record Challenge(UUID challengeId, Instant expiresAt) {}

    public record Envelope(UUID challengeId, String encrypted) {}

    public record ClearMessage(String mobile, String code) {}

    private ApplicationProblemException invalid() {
        return new ApplicationProblemException(401, ErrorCode.INVALID_CODE);
    }

    private static ApplicationProblemException field(String field, String detail) {
        return new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem(field, detail)));
    }

    private String purpose(UUID id) {
        return "customer-code:" + TenantContext.require().id() + ":" + id;
    }

    private void event(Actor actor, String action, String subject) {
        audit.record(new AuditAction(actor, "identity." + action, "customer_challenge", subject, null, null, null));
    }

    public Challenge request(String mobile, String channel, String label, String deviceId, String address) {
        TenantContext.require();
        String number;
        try {
            number = Identifiers.mobile(mobile);
        } catch (IllegalArgumentException ex) {
            throw field("mobile", "must be a Sri Lankan mobile number");
        }
        if (number == null) throw field("mobile", "is required");
        if (!Set.of("sms", "whatsapp").contains(channel == null ? "" : channel))
            throw field("channel", "must be sms or whatsapp");
        if (label == null
                || label.isBlank()
                || label.codePointCount(0, label.length()) > 80
                || label.codePoints().anyMatch(Character::isISOControl))
            throw field("deviceLabel", "must be plain text between 1 and 80 characters");
        if (deviceId == null) throw field("device", "requires a server-issued device");
        if (!environment.matchesProfiles("local"))
            throw new ApplicationProblemException(503, ErrorCode.PROVIDER_UNAVAILABLE);
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
                RateBucket row = candidate.row();
                row.windowStart = candidate.start();
                row.attempts = candidate.attempts() + 1;
                row.updatedAt = now;
                if (!em.contains(row)) em.persist(row);
            }
            RateBucket daily = buckets.getLast().row();
            int threshold = (int) Math.ceil(settings.dailyLimit() * .8);
            if (daily.attempts == threshold) {
                String day = now.atZone(ZoneOffset.UTC).toLocalDate().toString();
                event(Actor.system("code-volume"), "code_volume_alert", day);
                meters.counter(
                                "salon.identity.code.volume.alerts",
                                "tenant.id",
                                TenantContext.require().id().toString())
                        .increment();
            }
            for (CustomerChallenge previous : em.createQuery(
                            "select c from CustomerChallenge c where"
                                    + " c.numberHash=:n and c.invalidatedAt is null and"
                                    + " c.consumedAt is null",
                            CustomerChallenge.class)
                    .setParameter("n", numberHash)
                    .getResultList()) {
                previous.invalidatedAt = now;
                previous.updatedAt = now;
                if (outbox.cancel(previous.messageId, now))
                    event(Actor.system("code-request"), "code_cancelled", previous.id.toString());
            }
            CustomerChallenge c = new CustomerChallenge();
            c.id = UUID.randomUUID();
            c.createdAt = now;
            c.updatedAt = now;
            c.numberHash = numberHash;
            c.encryptedRecipient = crypto.encrypt(purpose(c.id) + ":recipient", number);
            c.deviceHash = crypto.hash("customer-device", deviceId);
            c.deviceLabel = label;
            c.channel = channel;
            c.expiresAt = now.plusSeconds(300);
            String code = String.format(Locale.ROOT, "%06d", random.nextInt(1000000));
            c.codeHash = crypto.hash(purpose(c.id), code);
            String encrypted = crypto.encrypt(purpose(c.id), json.writeValueAsString(new ClearMessage(number, code)));
            c.messageId = outbox.enqueue(channel, new Envelope(c.id, encrypted), now);
            em.persist(c);
            event(Actor.system("code-request"), "code_requested", c.id.toString());
            return new Challenge(c.id, c.expiresAt);
        });
    }

    private record Candidate(RateBucket row, Instant start, int attempts) {}

    /** Compute windows without writing: denied requests persist only their security audit. */
    private Candidate bucket(String dimension, String hash, Instant now, boolean daily) {
        RateBucket row = em.createQuery(
                        "select b from RateBucket b where b.dimension=:d and b.keyHash=:h", RateBucket.class)
                .setParameter("d", dimension)
                .setParameter("h", hash)
                .getResultStream()
                .findFirst()
                .orElse(null);
        Instant start = daily ? now.truncatedTo(ChronoUnit.DAYS) : now;
        if (row == null) {
            row = new RateBucket();
            row.id = UUID.randomUUID();
            row.createdAt = now;
            row.updatedAt = now;
            row.dimension = dimension;
            row.keyHash = hash;
            return new Candidate(row, start, 0);
        }
        boolean expired = daily ? !row.windowStart.equals(start) : !now.isBefore(row.windowStart.plusSeconds(900));
        return new Candidate(row, expired ? start : row.windowStart, expired ? 0 : row.attempts);
    }

    public StaffAccess.Tokens verify(UUID id, String code, String deviceId) {
        return work.run(() -> {
            CustomerChallenge c = em.createQuery(
                            "select c from CustomerChallenge c where c.id=:id", CustomerChallenge.class)
                    .setParameter("id", id)
                    .getResultStream()
                    .findFirst()
                    .orElseThrow(NotFoundException::new);
            if (c.invalidatedAt != null || c.consumedAt != null || c.attempts >= 5) {
                event(Actor.system("code-sign-in"), "customer_sign_in_failed", c.id.toString());
                throw invalid();
            }
            if (!clock.instant().isBefore(c.expiresAt)) {
                event(Actor.system("code-sign-in"), "customer_sign_in_expired", c.id.toString());
                throw new ApplicationProblemException(401, ErrorCode.CODE_EXPIRED);
            }
            if (deviceId == null || !crypto.equalHash(c.deviceHash, crypto.hash("customer-device", deviceId))) {
                event(Actor.system("code-sign-in"), "customer_sign_in_failed", c.id.toString());
                throw invalid();
            }
            c.attempts++;
            c.updatedAt = clock.instant();
            if (code == null
                    || !code.matches("[0-9]{6}")
                    || !crypto.equalHash(c.codeHash, crypto.hash(purpose(c.id), code))) {
                event(Actor.system("code-sign-in"), "customer_sign_in_failed", c.id.toString());
                throw invalid();
            }
            String mobile = crypto.decrypt(purpose(c.id) + ":recipient", c.encryptedRecipient);
            CustomerAccount account = em.createQuery(
                            "select a from CustomerAccount a where" + " a.mobile=:mobile", CustomerAccount.class)
                    .setParameter("mobile", mobile)
                    .getResultStream()
                    .findFirst()
                    .orElse(null);
            if (account == null) {
                account = new CustomerAccount();
                account.id = UUID.randomUUID();
                account.createdAt = clock.instant();
                account.updatedAt = account.createdAt;
                account.mobile = mobile;
                account.name = "";
                account.active = true;
                em.persist(account);
            }
            if (!account.active) throw invalid();
            c.consumedAt = clock.instant();
            if (outbox.cancel(c.messageId, clock.instant()))
                event(Actor.system("code-sign-in"), "code_cancelled", c.id.toString());
            StaffAccess.Tokens credentials = sessions.newCustomer(account, c.deviceLabel);
            audit.record(new AuditAction(
                    new Actor(ActorKind.CUSTOMER, account.id.toString()),
                    "identity.sign_in",
                    "session",
                    credentials.sessionId().toString(),
                    null,
                    null,
                    null));
            return credentials;
        });
    }

    /**
     * Called outside provider I/O after its delay, then atomically rechecks challenge state before
     * capture.
     */
    public boolean captureIfLive(OutboxDelivery delivery, Consumer<ClearMessage> capture) {
        Envelope payload = json.readValue(delivery.payload(), Envelope.class);
        return work.run(() -> {
            CustomerChallenge c = em.createQuery(
                            "select c from CustomerChallenge c where c.id=:id", CustomerChallenge.class)
                    .setParameter("id", payload.challengeId())
                    .getResultStream()
                    .findFirst()
                    .orElse(null);
            if (c == null
                    || !c.messageId.equals(delivery.messageId())
                    || !c.channel.equals(delivery.channel())
                    || c.invalidatedAt != null
                    || c.consumedAt != null
                    || !clock.instant().isBefore(c.expiresAt)) {
                if (outbox.cancel(delivery.messageId(), clock.instant()))
                    event(
                            Actor.system("code-delivery"),
                            "code_cancelled",
                            payload.challengeId().toString());
                return false;
            }
            ClearMessage clear = json.readValue(crypto.decrypt(purpose(c.id), payload.encrypted()), ClearMessage.class);
            if (!crypto.equalHash(c.codeHash, crypto.hash(purpose(c.id), clear.code()))
                    || !crypto.equalHash(c.numberHash, crypto.hash("customer-number", clear.mobile())))
                throw new IllegalStateException("Invalid encrypted message envelope");
            capture.accept(clear);
            return true;
        });
    }

    public Optional<UUID> messageFor(UUID challenge) {
        return work.run(
                () -> em.createQuery("select c from CustomerChallenge c where c.id=:id", CustomerChallenge.class)
                        .setParameter("id", challenge)
                        .getResultStream()
                        .findFirst()
                        .map(c -> c.messageId));
    }

    public OutboxMessageView delivery(UUID challenge) {
        return work.run(() -> {
            UUID message = em.createQuery("select c.messageId from CustomerChallenge c where" + " c.id=:id", UUID.class)
                    .setParameter("id", challenge)
                    .getResultStream()
                    .findFirst()
                    .orElseThrow(NotFoundException::new);
            return outbox.find(message).orElseThrow(NotFoundException::new);
        });
    }

    public void resetCustomer() {
        work.run(() -> {
            synthetic();
            for (CustomerChallenge c : em.createQuery("select c from CustomerChallenge c", CustomerChallenge.class)
                    .getResultList()) outbox.cancel(c.messageId, clock.instant());
            em.createQuery("delete from CustomerChallenge").executeUpdate();
            em.createQuery("delete from RefreshHistory h where h.sessionId in (select s.id"
                            + " from AccessSession s where s.kind='customer')")
                    .executeUpdate();
            em.createQuery("delete from AccessSession s where s.kind='customer'")
                    .executeUpdate();
            em.createQuery("delete from CustomerAccount").executeUpdate();
            em.createQuery("delete from RateBucket b where b.dimension like 'customer-%'")
                    .executeUpdate();
            return null;
        });
    }

    public void cleanup(Instant now) {
        for (CustomerChallenge c : em.createQuery(
                        "select c from CustomerChallenge c where c.expiresAt<=:now", CustomerChallenge.class)
                .setParameter("now", now)
                .getResultList()) outbox.cancel(c.messageId, now);
        em.createQuery("delete from CustomerChallenge c where c.expiresAt<=:now")
                .setParameter("now", now)
                .executeUpdate();
        em.createQuery("delete from RateBucket b where b.dimension like 'customer-%' and"
                        + " b.dimension<>'customer-day' and b.windowStart<=:before")
                .setParameter("before", now.minusSeconds(900))
                .executeUpdate();
    }

    private static void synthetic() {
        CurrentTenant t = TenantContext.require();
        Map<String, UUID> ids = Map.of(
                "alpha",
                UUID.fromString("a1a1a1a1-0000-4000-8000-000000000001"),
                "beta",
                UUID.fromString("b2b2b2b2-0000-4000-8000-000000000002"),
                "gamma",
                UUID.fromString("c3c3c3c3-0000-4000-8000-000000000003"));
        // Local fixtures work for every local salon (F03-S02: provisioned salons too, so read-only
        // rights can be checked on a billed salon); a salon named like a fixed synthetic salon
        // must still be that salon.
        if (ids.containsKey(t.slug()) && !t.id().equals(ids.get(t.slug()))) throw new NotFoundException();
    }
}
