package com.salonplatform.identity.application;

import com.salonplatform.identity.IdentityFacade.Principal;
import com.salonplatform.identity.domain.*;
import com.salonplatform.identity.infrastructure.IdentityCrypto;
import com.salonplatform.shared.api.*;
import com.salonplatform.shared.audit.*;
import com.salonplatform.shared.tenancy.*;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * One tenant transaction per use case. Security failures return after commit so counters/audit
 * persist.
 */
@Service
public class StaffAccess {
    @PersistenceContext private EntityManager em;
    private final IdentityWork identityWork;
    private final SessionAccess sessionAccess;
    private final IdentityCrypto crypto;
    private final Clock clock;
    private final AuditTrail audit;

    public StaffAccess(
            IdentityWork identityWork,
            SessionAccess sessionAccess,
            IdentityCrypto crypto,
            Clock clock,
            AuditTrail audit) {
        this.identityWork = identityWork;
        this.sessionAccess = sessionAccess;
        this.crypto = crypto;
        this.clock = clock;
        this.audit = audit;
    }

    @io.swagger.v3.oas.annotations.media.Schema(
            requiredProperties = {
                "state",
                "sessionId",
                "accessToken",
                "refreshToken",
                "accessExpiresAt",
                "refreshExpiresAt"
            })
    public record Tokens(
            String state,
            UUID sessionId,
            String accessToken,
            String refreshToken,
            Instant accessExpiresAt,
            Instant refreshExpiresAt) {}

    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @io.swagger.v3.oas.annotations.media.Schema(requiredProperties = {"state"})
    public record SignIn(
            String state,
            UUID challengeId,
            String challengeToken,
            Instant expiresAt,
            Tokens credentials) {}

    public record Enrollment(
            UUID challengeId, String secret, String otpauthUri, Instant expiresAt) {}

    public record StaffView(
            UUID id,
            String email,
            String mobile,
            String role,
            boolean active,
            boolean professionalGrant,
            boolean mfaEnabled,
            long version) {}

    public record SessionView(
            UUID id, String deviceLabel, Instant createdAt, Instant expiresAt, boolean current) {}

    public record Page<T>(List<T> items, int page, int size, long total) {}

    public record AuditView(
            UUID id,
            Instant occurredAt,
            String actorKind,
            String actorId,
            String action,
            String subjectKind,
            String subjectId,
            Map<String, Object> before,
            Map<String, Object> after) {}

    public record StaffInput(
            String email, String mobile, String password, String role, Boolean professionalGrant) {}

    public record StaffChange(long version, String role, Boolean professionalGrant) {}

    public record SeedAccount(
            String email,
            String mobile,
            String password,
            String role,
            String totpSecret,
            Boolean professionalGrant) {}

    private <T> T work(Supplier<T> useCase) {
        return identityWork.run(useCase);
    }

    private <T> T fresh(T entity) {
        em.persist(entity);
        return entity;
    }

    private void stamp(Object entity) {
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        if (entity instanceof StaffAccount e) {
            e.id = id;
            e.createdAt = now;
            e.updatedAt = now;
        } else if (entity instanceof AccessSession e) {
            e.id = id;
            e.createdAt = now;
            e.updatedAt = now;
        } else if (entity instanceof MfaChallenge e) {
            e.id = id;
            e.createdAt = now;
            e.updatedAt = now;
        } else if (entity instanceof RefreshHistory e) {
            e.id = id;
            e.createdAt = now;
            e.updatedAt = now;
        } else if (entity instanceof RateBucket e) {
            e.id = id;
            e.createdAt = now;
            e.updatedAt = now;
        } else if (entity instanceof MfaStep e) {
            e.id = id;
            e.createdAt = now;
            e.updatedAt = now;
        }
    }

    private ApplicationProblemException invalid() {
        return new ApplicationProblemException(401, ErrorCode.INVALID_CREDENTIALS);
    }

    private ApplicationProblemException sessionError() {
        return new ApplicationProblemException(401, ErrorCode.INVALID_SESSION);
    }

    private ApplicationProblemException conflict() {
        return new ApplicationProblemException(409, ErrorCode.CONFLICT);
    }

    private static ApplicationProblemException field(String name, String detail) {
        return new ApplicationProblemException(
                400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem(name, detail)));
    }

    private void event(
            Actor actor,
            String action,
            String kind,
            String id,
            Map<String, ?> before,
            Map<String, ?> after) {
        audit.record(new AuditAction(actor, "identity." + action, kind, id, null, before, after));
    }

    private void event(StaffAccount actor, String action, String kind, String id) {
        event(new Actor(ActorKind.STAFF, actor.id.toString()), action, kind, id, null, null);
    }

    private StaffAccount account(UUID id) {
        return em.createQuery("select a from StaffAccount a where a.id=:id", StaffAccount.class)
                .setParameter("id", id)
                .getResultStream()
                .findFirst()
                .orElseThrow(NotFoundException::new);
    }

    private AccessSession session(UUID id) {
        return em.createQuery("select s from AccessSession s where s.id=:id", AccessSession.class)
                .setParameter("id", id)
                .getResultStream()
                .findFirst()
                .orElseThrow(NotFoundException::new);
    }

    private StaffRole role(StaffAccount a) {
        return StaffRole.valueOf(a.role);
    }

    private record Auth(StaffAccount account, AccessSession session) {}

    private Auth auth() {
        SessionAccess.Auth a = sessionAccess.staffAuth();
        return new Auth(a.staff(), a.session());
    }

    private boolean live(StaffAccount a, AccessSession s) {
        return a.active
                && s.revokedAt == null
                && clock.instant().isBefore(s.expiresAt)
                && "staff".equals(s.kind)
                && (role(a) != StaffRole.OWNER || (a.mfaSecret != null && s.mfaVerified));
    }

    private Auth permitted(String permission) {
        Auth a = auth();
        if (!role(a.account()).permissions(a.account().professionalGrant).contains(permission))
            throw new ApplicationProblemException(403, ErrorCode.PERMISSION_DENIED);
        return a;
    }

    private Principal principal(StaffAccount a) {
        StaffRole r = role(a);
        return new Principal(
                a.id,
                "staff",
                a.role,
                r.permissions(a.professionalGrant),
                r.scope(a.professionalGrant),
                a.id,
                a.mfaSecret != null);
    }

    public Principal me() {
        return work(() -> principal(auth().account()));
    }

    public SignIn signIn(String identifier, String password, String label, String address) {
        return work(
                () -> {
                    String key;
                    try {
                        key = Identifiers.identifier(identifier);
                    } catch (IllegalArgumentException ex) {
                        key =
                                identifier == null
                                        ? ""
                                        : identifier.trim().toLowerCase(java.util.Locale.ROOT);
                    }
                    boolean identifierAllowed = attempt("staff-identifier", key, 10);
                    boolean addressAllowed = attempt("staff-address", address, 30);
                    if (!identifierAllowed || !addressAllowed) {
                        event(
                                Actor.system("sign-in"),
                                "sign_in_limited",
                                "staff",
                                crypto.hash("audit", key),
                                null,
                                null);
                        throw new ApplicationProblemException(429, ErrorCode.RATE_LIMITED);
                    }
                    StaffAccount a =
                            em.createQuery(
                                            "select a from StaffAccount a where a.email=:key or"
                                                    + " a.mobile=:key",
                                            StaffAccount.class)
                                    .setParameter("key", key)
                                    .getResultStream()
                                    .findFirst()
                                    .orElse(null);
                    boolean correct =
                            crypto.matchesPassword(password, a == null ? null : a.passwordHash);
                    if (a == null || !a.active || !correct) {
                        event(
                                Actor.system("sign-in"),
                                "sign_in_failed",
                                "staff",
                                a == null ? crypto.hash("audit", key) : a.id.toString(),
                                null,
                                null);
                        throw invalid();
                    }
                    device(label);
                    if (a.mfaSecret != null || role(a) == StaffRole.OWNER)
                        return challenge(
                                a, null, a.mfaSecret == null ? "enroll_mfa" : "verify_mfa", label);
                    Tokens tokens = newSession(a, label, false);
                    event(a, "sign_in", "session", tokens.sessionId().toString());
                    return new SignIn("authenticated", null, null, null, tokens);
                });
    }

    private boolean attempt(String dimension, String key, int ceiling) {
        String h = crypto.hash("rate-" + dimension, key);
        Instant now = clock.instant();
        RateBucket b =
                em.createQuery(
                                "select b from RateBucket b where b.dimension=:d and b.keyHash=:h",
                                RateBucket.class)
                        .setParameter("d", dimension)
                        .setParameter("h", h)
                        .getResultStream()
                        .findFirst()
                        .orElse(null);
        if (b == null) {
            b = new RateBucket();
            stamp(b);
            b.dimension = dimension;
            b.keyHash = h;
            b.windowStart = now;
            fresh(b);
        }
        if (!now.isBefore(b.windowStart.plusSeconds(900))) {
            b.windowStart = now;
            b.attempts = 0;
        }
        b.attempts++;
        b.updatedAt = now;
        return b.attempts <= ceiling;
    }

    private static void device(String label) {
        if (label == null
                || label.isBlank()
                || label.codePointCount(0, label.length()) > 80
                || label.codePoints().anyMatch(Character::isISOControl))
            throw field("deviceLabel", "must be plain text between 1 and 80 characters");
    }

    private SignIn challenge(StaffAccount a, AccessSession session, String purpose, String label) {
        MfaChallenge c = new MfaChallenge();
        stamp(c);
        c.accountId = a.id;
        c.sessionId = session == null ? null : session.id;
        c.purpose = purpose;
        c.deviceLabel = label;
        String token = crypto.randomToken();
        c.credentialHash = crypto.hash("challenge", token);
        c.expiresAt = clock.instant().plusSeconds(300);
        fresh(c);
        return new SignIn(purpose, c.id, token, c.expiresAt, null);
    }

    public SignIn voluntaryMfa() {
        return work(
                () -> {
                    Auth current = permitted("mfa.enroll");
                    if (current.account().mfaSecret != null) throw conflict();
                    return challenge(
                            current.account(),
                            current.session(),
                            "enroll_mfa",
                            current.session().deviceLabel);
                });
    }

    private MfaChallenge verifiedChallenge(UUID id, String token) {
        MfaChallenge c =
                em.createQuery("select c from MfaChallenge c where c.id=:id", MfaChallenge.class)
                        .setParameter("id", id)
                        .getResultStream()
                        .findFirst()
                        .orElseThrow(this::invalid);
        if (!crypto.equalHash(
                c.credentialHash, crypto.hash("challenge", token == null ? "" : token)))
            throw invalid();
        if (c.consumedAt != null || !clock.instant().isBefore(c.expiresAt) || c.attempts >= 5)
            throw invalid();
        StaffAccount a = account(c.accountId);
        if (!a.active) throw invalid();
        if (c.sessionId != null && !live(a, session(c.sessionId))) throw invalid();
        return c;
    }

    public Enrollment enroll(UUID id, String token) {
        return work(
                () -> {
                    MfaChallenge c = verifiedChallenge(id, token);
                    if (!"enroll_mfa".equals(c.purpose)) throw invalid();
                    StaffAccount a = account(c.accountId);
                    if (a.mfaSecret != null) throw conflict();
                    String purpose = "totp:" + TenantContext.require().id() + ":" + a.id;
                    if (c.pendingSecret == null)
                        c.pendingSecret = crypto.encrypt(purpose, crypto.newTotpSecret());
                    String secret = crypto.decrypt(purpose, c.pendingSecret);
                    String uri =
                            "otpauth://totp/Salon:"
                                    + a.id
                                    + "?secret="
                                    + secret
                                    + "&issuer=Salon&algorithm=SHA1&digits=6&period=30";
                    c.updatedAt = clock.instant();
                    return new Enrollment(c.id, secret, uri, c.expiresAt);
                });
    }

    public Tokens verifyMfa(UUID id, String token, String code) {
        return work(
                () -> {
                    MfaChallenge c = verifiedChallenge(id, token);
                    StaffAccount a = account(c.accountId);
                    c.attempts++;
                    c.updatedAt = clock.instant();
                    if ("enroll_mfa".equals(c.purpose) && a.mfaSecret != null) throw invalid();
                    String encrypted =
                            "enroll_mfa".equals(c.purpose) ? c.pendingSecret : a.mfaSecret;
                    if (encrypted == null) {
                        event(a, "mfa_failed", "challenge", c.id.toString());
                        throw invalid();
                    }
                    String secret =
                            crypto.decrypt(
                                    "totp:" + TenantContext.require().id() + ":" + a.id, encrypted);
                    Long step = crypto.matchingStep(secret, code);
                    boolean used =
                            step != null
                                    && !em.createQuery(
                                                    "select t from MfaStep t where t.accountId=:a"
                                                            + " and t.step=:s",
                                                    MfaStep.class)
                                            .setParameter("a", a.id)
                                            .setParameter("s", step)
                                            .getResultList()
                                            .isEmpty();
                    if (step == null || used) {
                        event(a, "mfa_failed", "challenge", c.id.toString());
                        throw invalid();
                    }
                    MfaStep consumed = new MfaStep();
                    stamp(consumed);
                    consumed.accountId = a.id;
                    consumed.step = step;
                    fresh(consumed);
                    c.consumedAt = clock.instant();
                    if ("enroll_mfa".equals(c.purpose)) {
                        if (a.mfaSecret != null) throw invalid();
                        a.mfaSecret = c.pendingSecret;
                        a.updatedAt = clock.instant();
                        revokeAccount(a.id, null);
                        event(a, "mfa_enrolled", "staff", a.id.toString());
                    }
                    Tokens tokens = newSession(a, c.deviceLabel, true);
                    event(a, "sign_in", "session", tokens.sessionId().toString());
                    return tokens;
                });
    }

    private Tokens newSession(StaffAccount a, String label, boolean mfa) {
        return sessionAccess.newStaff(a, label, mfa);
    }

    private Tokens rotate(StaffAccount a, AccessSession s, boolean remember) {
        return sessionAccess.rotate(s, remember);
    }

    private void revokeAccount(UUID accountId, UUID keep) {
        sessionAccess.revokeOwner("staff", accountId, keep);
    }

    public Tokens refresh(String token) {
        return sessionAccess.refresh(token);
    }

    public Page<SessionView> sessions(int page, int size) {
        return sessionAccess.sessions(page, size);
    }

    public void revoke(UUID id) {
        sessionAccess.revoke(id);
    }

    public void revokeAll() {
        sessionAccess.revokeAll();
    }

    public Tokens password(String oldPassword, String newPassword) {
        return work(
                () -> {
                    Auth a = permitted("password.change");
                    if (!crypto.matchesPassword(oldPassword, a.account().passwordHash)) {
                        event(a.account(), "password_failed", "staff", a.account().id.toString());
                        throw invalid();
                    }
                    passwordPolicy(newPassword, "newPassword");
                    a.account().passwordHash = crypto.encodePassword(newPassword);
                    a.account().updatedAt = clock.instant();
                    revokeAccount(a.account().id, a.session().id);
                    event(a.account(), "password_changed", "staff", a.account().id.toString());
                    return rotate(a.account(), a.session(), true);
                });
    }

    private void passwordPolicy(String password, String field) {
        if (!PasswordPolicy.valid(password))
            throw field(
                    field, "must contain 15 to 128 characters and must not be a common password");
    }

    private StaffRole parseRole(String value) {
        try {
            return StaffRole.valueOf(value);
        } catch (Exception ex) {
            throw field("role", "must be one of the five staff roles");
        }
    }

    private String email(String value) {
        try {
            return Identifiers.email(value);
        } catch (IllegalArgumentException ex) {
            throw field("email", "must be a valid email address");
        }
    }

    private String mobile(String value) {
        try {
            return Identifiers.mobile(value);
        } catch (IllegalArgumentException ex) {
            throw field("mobile", "must be a Sri Lankan mobile number");
        }
    }

    private StaffView view(StaffAccount a) {
        return new StaffView(
                a.id,
                a.email,
                a.mobile,
                a.role,
                a.active,
                a.professionalGrant,
                a.mfaSecret != null,
                a.version);
    }

    private Map<String, Object> accessValues(StaffAccount a) {
        return Map.of("role", a.role, "professionalGrant", a.professionalGrant, "active", a.active);
    }

    private StaffAccount createAccount(StaffInput input) {
        String email = email(input.email()), mobile = mobile(input.mobile());
        if (email == null && mobile == null) throw field("email", "email or mobile is required");
        StaffRole r = parseRole(input.role());
        boolean grant = Boolean.TRUE.equals(input.professionalGrant());
        if (grant && r != StaffRole.PROFESSIONAL)
            throw field("professionalGrant", "applies only to a Professional");
        passwordPolicy(input.password(), "password");
        if (em.createQuery(
                                "select count(a) from StaffAccount a where (:email is not null and"
                                        + " a.email=:email) or (:mobile is not null and"
                                        + " a.mobile=:mobile)",
                                Long.class)
                        .setParameter("email", email)
                        .setParameter("mobile", mobile)
                        .getSingleResult()
                > 0) throw conflict();
        StaffAccount a = new StaffAccount();
        stamp(a);
        a.email = email;
        a.mobile = mobile;
        a.passwordHash = crypto.encodePassword(input.password());
        a.role = r.name();
        a.active = true;
        a.professionalGrant = grant;
        fresh(a);
        return a;
    }

    public StaffView create(StaffInput input) {
        return work(
                () -> {
                    Auth current = permitted("staff.manage");
                    StaffAccount a = createAccount(input);
                    event(
                            new Actor(ActorKind.STAFF, current.account().id.toString()),
                            "staff_created",
                            "staff",
                            a.id.toString(),
                            null,
                            accessValues(a));
                    em.flush();
                    return view(a);
                });
    }

    public Page<StaffView> staff(int page, int size) {
        pagination(page, size);
        return work(
                () -> {
                    permitted("staff.list");
                    List<StaffAccount> rows =
                            em.createQuery(
                                            "select a from StaffAccount a order by"
                                                    + " a.createdAt,a.id",
                                            StaffAccount.class)
                                    .setFirstResult(page * size)
                                    .setMaxResults(size)
                                    .getResultList();
                    long total =
                            em.createQuery("select count(a) from StaffAccount a", Long.class)
                                    .getSingleResult();
                    return new Page<>(rows.stream().map(this::view).toList(), page, size, total);
                });
    }

    public StaffView change(UUID id, StaffChange change) {
        return work(
                () -> {
                    Auth actor = permitted("staff.manage");
                    StaffAccount a = account(id);
                    if (a.version != change.version()) throw conflict();
                    if (!a.active) throw conflict();
                    StaffRole next = change.role() == null ? role(a) : parseRole(change.role());
                    boolean grant =
                            change.professionalGrant() == null
                                    ? a.professionalGrant
                                    : change.professionalGrant();
                    if (grant && next != StaffRole.PROFESSIONAL)
                        throw field("professionalGrant", "applies only to a Professional");
                    protectLastOwner(a, next, false);
                    Map<String, Object> before = accessValues(a);
                    String old = a.role;
                    a.role = next.name();
                    a.professionalGrant = grant;
                    a.updatedAt = clock.instant();
                    if (next == StaffRole.OWNER && !old.equals(next.name()))
                        revokeAccount(a.id, null);
                    event(
                            new Actor(ActorKind.STAFF, actor.account().id.toString()),
                            "staff_changed",
                            "staff",
                            a.id.toString(),
                            before,
                            accessValues(a));
                    em.flush();
                    return view(a);
                });
    }

    public void remove(UUID id) {
        work(
                () -> {
                    Auth actor = permitted("staff.manage");
                    StaffAccount a = account(id);
                    protectLastOwner(a, role(a), true);
                    Map<String, Object> before = accessValues(a);
                    a.active = false;
                    a.updatedAt = clock.instant();
                    revokeAccount(a.id, null);
                    event(
                            new Actor(ActorKind.STAFF, actor.account().id.toString()),
                            "staff_removed",
                            "staff",
                            a.id.toString(),
                            before,
                            accessValues(a));
                    return null;
                });
    }

    private void protectLastOwner(StaffAccount a, StaffRole next, boolean remove) {
        if (a.active
                && role(a) == StaffRole.OWNER
                && (remove || next != StaffRole.OWNER)
                && em.createQuery(
                                        "select count(a) from StaffAccount a where a.active=true"
                                                + " and a.role='OWNER'",
                                        Long.class)
                                .getSingleResult()
                        <= 1) throw conflict();
    }

    public Page<AuditView> audits(int page, int size, String subjectId) {
        pagination(page, size);
        return work(
                () -> {
                    permitted("audit.list");
                    AuditTrail.AuditPage rows = audit.identityPage(page, size, subjectId);
                    return new Page<>(
                            rows.items().stream()
                                    .map(
                                            r ->
                                                    new AuditView(
                                                            r.id(),
                                                            r.occurredAt(),
                                                            r.actorKind(),
                                                            r.actorId(),
                                                            r.action(),
                                                            r.subjectKind(),
                                                            r.subjectId(),
                                                            safeAuditValues(r.before()),
                                                            safeAuditValues(r.after())))
                                    .toList(),
                            page,
                            size,
                            rows.total());
                });
    }

    private Map<String, Object> safeAuditValues(Map<String, Object> input) {
        if (input == null) return null;
        java.util.HashMap<String, Object> result = new java.util.HashMap<>();
        for (String k : List.of("role", "professionalGrant", "active"))
            if (input.containsKey(k)) result.put(k, input.get(k));
        return Map.copyOf(result);
    }

    private static void pagination(int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100)
            throw field("page", "page must be 0 to 100000 and size 1 to 100");
    }

    /**
     * Local fixtures are validated outside this service too; no production route exposes this
     * method.
     */
    public List<StaffView> seed(List<SeedAccount> inputs) {
        return work(
                () -> {
                    synthetic();
                    java.util.ArrayList<StaffView> result = new java.util.ArrayList<>();
                    for (SeedAccount input : inputs) {
                        String email = email(input.email()), mobile = mobile(input.mobile());
                        StaffAccount a =
                                em.createQuery(
                                                "select a from StaffAccount a where (:e is not null"
                                                        + " and a.email=:e) or (:m is not null and"
                                                        + " a.mobile=:m)",
                                                StaffAccount.class)
                                        .setParameter("e", email)
                                        .setParameter("m", mobile)
                                        .getResultStream()
                                        .findFirst()
                                        .orElse(null);
                        if (a == null) {
                            a =
                                    createAccount(
                                            new StaffInput(
                                                    input.email(),
                                                    input.mobile(),
                                                    input.password(),
                                                    input.role(),
                                                    input.professionalGrant()));
                            if (input.totpSecret() != null) {
                                if (!input.totpSecret().matches("[A-Z2-7]{32}"))
                                    throw field("totpSecret", "must be a 20-byte base32 secret");
                                a.mfaSecret =
                                        crypto.encrypt(
                                                "totp:" + TenantContext.require().id() + ":" + a.id,
                                                input.totpSecret());
                            }
                        }
                        result.add(view(a));
                    }
                    em.flush();
                    return List.copyOf(result);
                });
    }

    public void reset() {
        work(
                () -> {
                    synthetic();
                    for (Class<?> entity :
                            List.of(
                                    CustomerChallenge.class,
                                    MfaStep.class,
                                    MfaChallenge.class,
                                    RefreshHistory.class,
                                    AccessSession.class,
                                    RateBucket.class,
                                    CustomerAccount.class,
                                    StaffAccount.class))
                        em.createQuery("delete from " + entity.getSimpleName()).executeUpdate();
                    return null;
                });
    }

    private static void synthetic() {
        CurrentTenant tenant = TenantContext.require();
        Map<String, UUID> ids =
                Map.of(
                        "alpha",
                        UUID.fromString("a1a1a1a1-0000-4000-8000-000000000001"),
                        "beta",
                        UUID.fromString("b2b2b2b2-0000-4000-8000-000000000002"),
                        "gamma",
                        UUID.fromString("c3c3c3c3-0000-4000-8000-000000000003"));
        if (!tenant.id().equals(ids.get(tenant.slug()))) throw new NotFoundException();
    }
}
