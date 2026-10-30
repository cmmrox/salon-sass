package com.salonplatform.identity.service.impl;

import com.salonplatform.identity.Principal;
import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.identity.domain.SessionKind;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.identity.dto.Tokens;
import com.salonplatform.identity.entity.AccessSession;
import com.salonplatform.identity.entity.CustomerAccount;
import com.salonplatform.identity.entity.RefreshHistory;
import com.salonplatform.identity.entity.StaffAccount;
import com.salonplatform.identity.repository.AccessSessionRepository;
import com.salonplatform.identity.repository.CustomerAccountRepository;
import com.salonplatform.identity.repository.RefreshHistoryRepository;
import com.salonplatform.identity.repository.StaffAccountRepository;
import com.salonplatform.shared.audit.Actor;
import com.salonplatform.shared.audit.ActorKind;
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
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The session mechanics identity's use cases share, on the session and account rows: who a request's session
 * belongs to, whether it is live, and a session's credentials. The use cases themselves are
 * {@link com.salonplatform.identity.service.SessionService}'s.
 */
@Component
@RequiredArgsConstructor
class SessionAccess {

    private static final Duration STAFF_SESSION = Duration.ofDays(7);

    private static final Duration CUSTOMER_SESSION = Duration.ofDays(30);

    private final AccessSessionRepository sessions;
    private final RefreshHistoryRepository refreshHistory;
    private final StaffAccountRepository staff;
    private final CustomerAccountRepository customers;
    private final IdentityCrypto crypto;
    private final Clock clock;

    record Auth(AccessSession session, StaffAccount staff, CustomerAccount customer) {
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

    static ApplicationProblemException invalid() {
        return new ApplicationProblemException(401, ErrorCode.INVALID_SESSION);
    }

    Auth auth() {
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

    Auth staffAuth() {
        Auth auth = auth();
        if (auth.staff() == null) {
            throw invalid();
        }
        return auth;
    }

    Auth customerAuth() {
        Auth auth = auth();
        if (auth.customer() == null) {
            throw invalid();
        }
        return auth;
    }

    Auth owner(AccessSession session) {
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

    boolean live(Auth auth) {
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

    /** The read-only principal of a support session: the Owner's reads, its own expiry, no staff id. */
    static Principal supportPrincipal(SupportGrant grant) {
        return new Principal(
                grant.sessionId(), "support", null, StaffRole.SUPPORT_READS, "salon", null, false, grant.expiresAt());
    }

    /** Whether the current request is a support session holding this read permission. */
    static boolean supportMay(String permission) {
        return RequestSupport.current().isPresent() && StaffRole.SUPPORT_READS.contains(permission);
    }

    Tokens newStaff(StaffAccount account, String label, boolean mfaVerified) {
        return open(AccessSession.forStaff(account.getId(), label, mfaVerified, clock.instant(), STAFF_SESSION));
    }

    Tokens newCustomer(UUID customerAccountId, String label) {
        return open(AccessSession.forCustomer(customerAccountId, label, clock.instant(), CUSTOMER_SESSION));
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
    Tokens rotate(AccessSession session, boolean remember) {
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

    /**
     * Revokes every live session of a staff member or customer.
     *
     * @param keep a session to leave signed in, or null for none
     */
    void revokeOwner(String kind, UUID owner, UUID keep) {
        List<AccessSession> live = SessionKind.STAFF.equals(kind)
                ? sessions.findByKindAndAccountIdAndRevokedAtIsNull(kind, owner)
                : sessions.findByKindAndCustomerAccountIdAndRevokedAtIsNull(kind, owner);
        for (AccessSession session : live) {
            if (keep == null || !session.getId().equals(keep)) {
                session.revoke(clock.instant());
            }
        }
    }
}
