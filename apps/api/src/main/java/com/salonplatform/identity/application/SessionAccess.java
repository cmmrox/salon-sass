package com.salonplatform.identity.application;

import com.salonplatform.identity.IdentityFacade.Principal;
import com.salonplatform.identity.domain.*;
import com.salonplatform.identity.infrastructure.IdentityCrypto;
import com.salonplatform.shared.api.*;
import com.salonplatform.shared.audit.*;
import com.salonplatform.shared.tenancy.*;

import jakarta.persistence.*;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/**
 * One session mechanism for both principal kinds; kind and owner are checked for every use case.
 */
@Service
public class SessionAccess {
    @PersistenceContext private EntityManager em;
    private final IdentityWork work;
    private final IdentityCrypto crypto;
    private final Clock clock;
    private final AuditTrail audit;

    public SessionAccess(IdentityWork work, IdentityCrypto crypto, Clock clock, AuditTrail audit) {
        this.work = work;
        this.crypto = crypto;
        this.clock = clock;
        this.audit = audit;
    }

    public record Auth(AccessSession session, StaffAccount staff, CustomerAccount customer) {
        public UUID ownerId() {
            return staff != null ? staff.id : customer.id;
        }

        public String kind() {
            return staff != null ? "staff" : "customer";
        }

        public Actor actor() {
            return new Actor(
                    staff != null ? ActorKind.STAFF : ActorKind.CUSTOMER, ownerId().toString());
        }
    }

    private ApplicationProblemException invalid() {
        return new ApplicationProblemException(401, ErrorCode.INVALID_SESSION);
    }

    public Auth auth() {
        IdentityCrypto.Claims claims = crypto.verify(RequestCredential.require());
        if (!claims.tenantId().equals(TenantContext.require().id())) throw new NotFoundException();
        AccessSession s =
                em.createQuery("select s from AccessSession s where s.id=:id", AccessSession.class)
                        .setParameter("id", claims.sessionId())
                        .getResultStream()
                        .findFirst()
                        .orElseThrow(this::invalid);
        Auth a = owner(s);
        if (!s.kind.equals(claims.kind())
                || !a.ownerId().equals(claims.accountId())
                || s.generation != claims.generation()
                || !live(a)) throw invalid();
        return a;
    }

    public Auth staffAuth() {
        Auth a = auth();
        if (a.staff() == null) throw invalid();
        return a;
    }

    public Auth customerAuth() {
        Auth a = auth();
        if (a.customer() == null) throw invalid();
        return a;
    }

    private Auth owner(AccessSession s) {
        if ("staff".equals(s.kind)) {
            StaffAccount a =
                    em.createQuery(
                                    "select a from StaffAccount a where a.id=:id",
                                    StaffAccount.class)
                            .setParameter("id", s.accountId)
                            .getResultStream()
                            .findFirst()
                            .orElseThrow(this::invalid);
            return new Auth(s, a, null);
        }
        if ("customer".equals(s.kind)) {
            CustomerAccount a =
                    em.createQuery(
                                    "select a from CustomerAccount a where a.id=:id",
                                    CustomerAccount.class)
                            .setParameter("id", s.customerAccountId)
                            .getResultStream()
                            .findFirst()
                            .orElseThrow(this::invalid);
            return new Auth(s, null, a);
        }
        throw invalid();
    }

    public boolean live(Auth a) {
        AccessSession s = a.session();
        if (s.revokedAt != null || !clock.instant().isBefore(s.expiresAt)) return false;
        if (a.customer() != null) return a.customer().active;
        if (!a.staff().active) return false;
        return !"OWNER".equals(a.staff().role) || (a.staff().mfaSecret != null && s.mfaVerified);
    }

    public Principal me() {
        return work.run(() -> principal(auth()));
    }

    private Principal principal(Auth a) {
        if (a.customer() != null)
            return new Principal(
                    a.ownerId(),
                    "customer",
                    null,
                    Set.of("session.manage", "profile.manage", "data.export", "account.delete"),
                    "own",
                    null,
                    false);
        StaffAccount s = a.staff();
        StaffRole r = StaffRole.valueOf(s.role);
        return new Principal(
                s.id,
                "staff",
                s.role,
                r.permissions(s.professionalGrant),
                r.scope(s.professionalGrant),
                s.id,
                s.mfaSecret != null);
    }

    public StaffAccess.Tokens newStaff(StaffAccount staff, String label, boolean mfa) {
        return create(staff.id, null, label, mfa, 7);
    }

    public StaffAccess.Tokens newCustomer(CustomerAccount customer, String label) {
        return create(null, customer.id, label, false, 30);
    }

    private StaffAccess.Tokens create(
            UUID staff, UUID customer, String label, boolean mfa, int days) {
        AccessSession s = new AccessSession();
        s.id = UUID.randomUUID();
        s.createdAt = clock.instant();
        s.updatedAt = s.createdAt;
        s.accountId = staff;
        s.customerAccountId = customer;
        s.kind = staff != null ? "staff" : "customer";
        s.deviceLabel = label;
        s.mfaVerified = mfa;
        s.expiresAt = s.createdAt.plus(Duration.ofDays(days));
        StaffAccess.Tokens tokens = rotate(s, false);
        em.persist(s);
        return tokens;
    }

    public StaffAccess.Tokens rotate(AccessSession s, boolean remember) {
        if (remember) {
            RefreshHistory h = new RefreshHistory();
            h.id = UUID.randomUUID();
            h.createdAt = clock.instant();
            h.updatedAt = h.createdAt;
            h.sessionId = s.id;
            h.credentialHash = s.refreshHash;
            em.persist(h);
        }
        String token = crypto.randomToken();
        s.refreshHash = crypto.hash("refresh", token);
        s.generation++;
        s.updatedAt = clock.instant();
        Instant issued = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        UUID owner = "staff".equals(s.kind) ? s.accountId : s.customerAccountId;
        return new StaffAccess.Tokens(
                "authenticated",
                s.id,
                crypto.access(
                        TenantContext.require().id(), owner, s.id, s.kind, s.generation, issued),
                token,
                issued.plusSeconds(300),
                s.expiresAt);
    }

    public StaffAccess.Tokens refresh(String token) {
        return work.run(
                () -> {
                    String hash = crypto.hash("refresh", token == null ? "" : token);
                    AccessSession s =
                            em.createQuery(
                                            "select s from AccessSession s where s.refreshHash=:h",
                                            AccessSession.class)
                                    .setParameter("h", hash)
                                    .getResultStream()
                                    .findFirst()
                                    .orElse(null);
                    if (s == null) {
                        RefreshHistory old =
                                em.createQuery(
                                                "select h from RefreshHistory h where"
                                                        + " h.credentialHash=:h",
                                                RefreshHistory.class)
                                        .setParameter("h", hash)
                                        .getResultStream()
                                        .findFirst()
                                        .orElse(null);
                        if (old != null) {
                            s = find(old.sessionId);
                            s.revokedAt = clock.instant();
                            s.updatedAt = clock.instant();
                            record(owner(s), "refresh_replay", "session", s.id);
                        }
                        throw invalid();
                    }
                    Auth a = owner(s);
                    if (!live(a)) throw invalid();
                    return rotate(s, true);
                });
    }

    private AccessSession find(UUID id) {
        return em.createQuery("select s from AccessSession s where s.id=:id", AccessSession.class)
                .setParameter("id", id)
                .getResultStream()
                .findFirst()
                .orElseThrow(NotFoundException::new);
    }

    public StaffAccess.Page<StaffAccess.SessionView> sessions(int page, int size) {
        pagination(page, size);
        return work.run(
                () -> {
                    Auth a = auth();
                    String ownerField = a.staff() != null ? "accountId" : "customerAccountId";
                    String condition =
                            " s."
                                    + ownerField
                                    + "=:a and s.kind=:kind and s.revokedAt is null and"
                                    + " s.expiresAt>:now";
                    List<AccessSession> rows =
                            em.createQuery(
                                            "select s from AccessSession s where"
                                                    + condition
                                                    + " order by s.createdAt desc,s.id",
                                            AccessSession.class)
                                    .setParameter("a", a.ownerId())
                                    .setParameter("kind", a.kind())
                                    .setParameter("now", clock.instant())
                                    .setFirstResult(page * size)
                                    .setMaxResults(size)
                                    .getResultList();
                    long total =
                            em.createQuery(
                                            "select count(s) from AccessSession s where"
                                                    + condition,
                                            Long.class)
                                    .setParameter("a", a.ownerId())
                                    .setParameter("kind", a.kind())
                                    .setParameter("now", clock.instant())
                                    .getSingleResult();
                    return new StaffAccess.Page<>(
                            rows.stream()
                                    .map(
                                            s ->
                                                    new StaffAccess.SessionView(
                                                            s.id,
                                                            s.deviceLabel,
                                                            s.createdAt,
                                                            s.expiresAt,
                                                            s.id.equals(a.session().id)))
                                    .toList(),
                            page,
                            size,
                            total);
                });
    }

    public void revoke(UUID id) {
        work.run(
                () -> {
                    Auth a = auth();
                    AccessSession s = find(id);
                    if (!s.kind.equals(a.kind())
                            || !(a.staff() != null ? s.accountId : s.customerAccountId)
                                    .equals(a.ownerId())) throw new NotFoundException();
                    s.revokedAt = clock.instant();
                    s.updatedAt = clock.instant();
                    record(a, "session_revoked", "session", s.id);
                    return null;
                });
    }

    public void revokeAll() {
        work.run(
                () -> {
                    Auth a = auth();
                    revokeOwner(a.kind(), a.ownerId(), null);
                    record(a, "sessions_revoked", a.kind(), a.ownerId());
                    return null;
                });
    }

    public void revokeOwner(String kind, UUID id, UUID keep) {
        String field = "staff".equals(kind) ? "accountId" : "customerAccountId";
        for (AccessSession s :
                em.createQuery(
                                "select s from AccessSession s where s.kind=:kind and s."
                                        + field
                                        + "=:id and s.revokedAt is null",
                                AccessSession.class)
                        .setParameter("kind", kind)
                        .setParameter("id", id)
                        .getResultList()) {
            if (keep == null || !s.id.equals(keep)) {
                s.revokedAt = clock.instant();
                s.updatedAt = clock.instant();
            }
        }
    }

    private void record(Auth a, String action, String kind, UUID id) {
        audit.record(
                new AuditAction(
                        a.actor(), "identity." + action, kind, id.toString(), null, null, null));
    }

    private static void pagination(int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100)
            throw new ApplicationProblemException(
                    400,
                    ErrorCode.BAD_REQUEST,
                    List.of(
                            new FieldProblem(
                                    "page", "page must be 0 to 100000 and size 1 to 100")));
    }
}
