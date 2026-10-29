package com.salonplatform.identity.application;

import com.salonplatform.identity.Principal;
import com.salonplatform.identity.domain.AccessSession;
import com.salonplatform.identity.domain.CustomerAccount;
import com.salonplatform.identity.domain.RefreshHistory;
import com.salonplatform.identity.domain.SessionKind;
import com.salonplatform.identity.domain.StaffAccount;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.identity.infrastructure.AccessSessionRepository;
import com.salonplatform.identity.infrastructure.CustomerAccountRepository;
import com.salonplatform.identity.infrastructure.IdentityCrypto;
import com.salonplatform.identity.infrastructure.RefreshHistoryRepository;
import com.salonplatform.identity.infrastructure.StaffAccountRepository;
import com.salonplatform.shared.audit.Actor;
import com.salonplatform.shared.audit.ActorKind;
import com.salonplatform.shared.audit.AuditAction;
import com.salonplatform.shared.audit.AuditTrail;
import com.salonplatform.shared.tenancy.RequestCredential;
import com.salonplatform.shared.tenancy.RequestSupport;
import com.salonplatform.shared.tenancy.SupportGrant;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.NotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** One session mechanism for both principal kinds; kind and owner are checked for every use case. */
@Service
@RequiredArgsConstructor
public class SessionAccess {

    private static final Duration STAFF_SESSION = Duration.ofDays(7);

    private static final Duration CUSTOMER_SESSION = Duration.ofDays(30);

    private final IdentityWork work;
    private final AccessSessionRepository sessions;
    private final RefreshHistoryRepository refreshHistory;
    private final StaffAccountRepository staff;
    private final CustomerAccountRepository customers;
    private final IdentityCrypto crypto;
    private final Clock clock;
    private final AuditTrail audit;

    public record Auth(AccessSession session, StaffAccount staff, CustomerAccount customer) {
        public UUID ownerId() {
            return staff != null ? staff.getId() : customer.getId();
        }

        public String kind() {
            return staff != null ? SessionKind.STAFF : SessionKind.CUSTOMER;
        }

        public Actor actor() {
            return new Actor(
                    staff != null ? ActorKind.STAFF : ActorKind.CUSTOMER,
                    ownerId().toString());
        }
    }

    private static ApplicationProblemException invalid() {
        return new ApplicationProblemException(401, ErrorCode.INVALID_SESSION);
    }

    public Auth auth() {
        // A support session (F03-S04) is never a staff or customer session: what it may read is
        // checked by the use cases that serve it; everything else answers permission denied.
        if (RequestSupport.current().isPresent()) {
            throw IdentityProblems.permissionDenied();
        }
        IdentityCrypto.Claims claims = crypto.verify(RequestCredential.require());
        if (!claims.tenantId().equals(TenantContext.require().id())) {
            throw new NotFoundException();
        }
        AccessSession session = sessions.findById(claims.sessionId()).orElseThrow(SessionAccess::invalid);
        Auth auth = owner(session);
        if (!session.getKind().equals(claims.kind())
                || !auth.ownerId().equals(claims.accountId())
                || session.getGeneration() != claims.generation()
                || !live(auth)) {
            throw invalid();
        }
        return auth;
    }

    public Auth staffAuth() {
        Auth auth = auth();
        if (auth.staff() == null) {
            throw invalid();
        }
        return auth;
    }

    public Auth customerAuth() {
        Auth auth = auth();
        if (auth.customer() == null) {
            throw invalid();
        }
        return auth;
    }

    private Auth owner(AccessSession session) {
        if (SessionKind.STAFF.equals(session.getKind())) {
            StaffAccount account = staff.findById(session.getAccountId()).orElseThrow(SessionAccess::invalid);
            return new Auth(session, account, null);
        }
        if (SessionKind.CUSTOMER.equals(session.getKind())) {
            CustomerAccount customer =
                    customers.findById(session.getCustomerAccountId()).orElseThrow(SessionAccess::invalid);
            return new Auth(session, null, customer);
        }
        throw invalid();
    }

    public boolean live(Auth auth) {
        AccessSession session = auth.session();
        if (session.getRevokedAt() != null || !clock.instant().isBefore(session.getExpiresAt())) {
            return false;
        }
        if (auth.customer() != null) {
            return auth.customer().isActive();
        }
        if (!auth.staff().isActive()) {
            return false;
        }
        return auth.staff().getRole() != StaffRole.OWNER || (auth.staff().hasMfa() && session.isMfaVerified());
    }

    public Principal me() {
        Optional<SupportGrant> support = RequestSupport.current();
        if (support.isPresent()) {
            return supportPrincipal(support.get());
        }
        return work.run(() -> principal(auth()));
    }

    /** The read-only principal of a support session: the Owner's reads, its own expiry, no staff id. */
    public static Principal supportPrincipal(SupportGrant grant) {
        return new Principal(
                grant.sessionId(), "support", null, StaffRole.SUPPORT_READS, "salon", null, false, grant.expiresAt());
    }

    /** Whether the current request is a support session holding this read permission. */
    public static boolean supportMay(String permission) {
        return RequestSupport.current().isPresent() && StaffRole.SUPPORT_READS.contains(permission);
    }

    private static Principal principal(Auth auth) {
        if (auth.customer() != null) {
            return new Principal(
                    auth.ownerId(),
                    SessionKind.CUSTOMER,
                    null,
                    Set.of("session.manage", "profile.manage", "data.export", "account.delete"),
                    "own",
                    null,
                    false);
        }
        StaffAccount account = auth.staff();
        StaffRole role = account.getRole();
        return new Principal(
                account.getId(),
                SessionKind.STAFF,
                role.name(),
                role.permissions(account.isProfessionalGrant()),
                role.scope(account.isProfessionalGrant()),
                account.getId(),
                account.hasMfa());
    }

    public Tokens newStaff(StaffAccount account, String label, boolean mfaVerified) {
        return open(AccessSession.forStaff(account.getId(), label, mfaVerified, clock.instant(), STAFF_SESSION));
    }

    public Tokens newCustomer(CustomerAccount customer, String label) {
        return open(AccessSession.forCustomer(customer.getId(), label, clock.instant(), CUSTOMER_SESSION));
    }

    private Tokens open(AccessSession session) {
        Tokens tokens = rotate(session, false);
        sessions.persist(session);
        return tokens;
    }

    /**
     * Issues the session's next refresh credential and access token.
     *
     * @param remember whether the credential being replaced is kept, so that presenting it again is a replay
     */
    public Tokens rotate(AccessSession session, boolean remember) {
        if (remember) {
            refreshHistory.persist(RefreshHistory.spent(session.getId(), session.getRefreshHash(), clock.instant()));
        }
        String token = crypto.randomToken();
        session.rotate(crypto.hash("refresh", token), clock.instant());
        Instant issued = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        return new Tokens(
                "authenticated",
                session.getId(),
                crypto.access(
                        TenantContext.require().id(),
                        session.ownerId(),
                        session.getId(),
                        session.getKind(),
                        session.getGeneration(),
                        issued),
                token,
                issued.plusSeconds(300),
                session.getExpiresAt());
    }

    public Tokens refresh(String token) {
        return work.run(() -> {
            String hash = crypto.hash("refresh", token == null ? "" : token);
            AccessSession session = sessions.findFirstByRefreshHash(hash).orElse(null);
            if (session == null) {
                Optional<RefreshHistory> replayed = refreshHistory.findFirstByCredentialHash(hash);
                if (replayed.isPresent()) {
                    AccessSession family = find(replayed.get().getSessionId());
                    family.revoke(clock.instant());
                    record(owner(family), "refresh_replay", "session", family.getId());
                }
                throw invalid();
            }
            Auth auth = owner(session);
            if (!live(auth)) {
                throw invalid();
            }
            return rotate(session, true);
        });
    }

    private AccessSession find(UUID id) {
        return sessions.findById(id).orElseThrow(NotFoundException::new);
    }

    public Page<SessionView> sessions(int page, int size) {
        Page.requireValid(page, size);
        return work.run(() -> {
            Auth auth = auth();
            PageRequest request = PageRequest.of(page, size);
            org.springframework.data.domain.Page<AccessSession> rows = auth.staff() != null
                    ? sessions.findLiveOfStaff(auth.ownerId(), clock.instant(), request)
                    : sessions.findLiveOfCustomer(auth.ownerId(), clock.instant(), request);
            UUID current = auth.session().getId();
            return new Page<>(
                    rows.stream()
                            .map(session -> new SessionView(
                                    session.getId(),
                                    session.getDeviceLabel(),
                                    session.getCreatedAt(),
                                    session.getExpiresAt(),
                                    session.getId().equals(current)))
                            .toList(),
                    page,
                    size,
                    rows.getTotalElements());
        });
    }

    public void revoke(UUID id) {
        work.run(() -> {
            Auth auth = auth();
            AccessSession session = find(id);
            if (!session.getKind().equals(auth.kind()) || !session.ownerId().equals(auth.ownerId())) {
                throw new NotFoundException();
            }
            session.revoke(clock.instant());
            record(auth, "session_revoked", "session", session.getId());
            return null;
        });
    }

    public void revokeAll() {
        work.run(() -> {
            Auth auth = auth();
            revokeOwner(auth.kind(), auth.ownerId(), null);
            record(auth, "sessions_revoked", auth.kind(), auth.ownerId());
            return null;
        });
    }

    /**
     * Revokes every live session of a staff member or customer.
     *
     * @param keep a session to leave signed in, or null for none
     */
    public void revokeOwner(String kind, UUID owner, UUID keep) {
        List<AccessSession> live = SessionKind.STAFF.equals(kind)
                ? sessions.findByKindAndAccountIdAndRevokedAtIsNull(kind, owner)
                : sessions.findByKindAndCustomerAccountIdAndRevokedAtIsNull(kind, owner);
        for (AccessSession session : live) {
            if (keep == null || !session.getId().equals(keep)) {
                session.revoke(clock.instant());
            }
        }
    }

    private void record(Auth auth, String action, String kind, UUID id) {
        audit.record(new AuditAction(auth.actor(), "identity." + action, kind, id.toString(), null, null, null));
    }
}
