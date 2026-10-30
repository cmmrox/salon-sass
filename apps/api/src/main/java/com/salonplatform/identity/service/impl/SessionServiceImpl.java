package com.salonplatform.identity.service.impl;

import com.salonplatform.identity.Principal;
import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.identity.domain.SessionKind;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.identity.dto.Page;
import com.salonplatform.identity.dto.SessionView;
import com.salonplatform.identity.dto.Tokens;
import com.salonplatform.identity.entity.AccessSession;
import com.salonplatform.identity.entity.RefreshHistory;
import com.salonplatform.identity.entity.StaffAccount;
import com.salonplatform.identity.repository.AccessSessionRepository;
import com.salonplatform.identity.repository.RefreshHistoryRepository;
import com.salonplatform.identity.service.IdentityWork;
import com.salonplatform.identity.service.SessionService;
import com.salonplatform.shared.audit.AuditAction;
import com.salonplatform.shared.audit.AuditTrail;
import com.salonplatform.shared.tenancy.RequestSupport;
import com.salonplatform.shared.tenancy.SupportGrant;
import com.salonplatform.shared.web.NotFoundException;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class SessionServiceImpl implements SessionService {

    private final IdentityWork work;
    private final SessionAccess access;
    private final AccessSessionRepository sessions;
    private final RefreshHistoryRepository refreshHistory;
    private final IdentityCrypto crypto;
    private final Clock clock;
    private final AuditTrail audit;

    @Override
    public Principal me() {
        Optional<SupportGrant> support = RequestSupport.current();
        if (support.isPresent()) {
            return SessionAccess.supportPrincipal(support.get());
        }
        return work.run(() -> principal(access.auth()));
    }

    private static Principal principal(SessionAccess.Auth auth) {
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

    @Override
    public Tokens refresh(String token) {
        return work.run(() -> {
            String hash = crypto.hash("refresh", token == null ? "" : token);
            AccessSession session = sessions.findFirstByRefreshHash(hash).orElse(null);
            if (session == null) {
                Optional<RefreshHistory> replayed = refreshHistory.findFirstByCredentialHash(hash);
                if (replayed.isPresent()) {
                    AccessSession family = find(replayed.get().getSessionId());
                    family.revoke(clock.instant());
                    record(access.owner(family), "refresh_replay", "session", family.getId());
                }
                throw SessionAccess.invalid();
            }
            SessionAccess.Auth auth = access.owner(session);
            if (!access.live(auth)) {
                throw SessionAccess.invalid();
            }
            return access.rotate(session, true);
        });
    }

    private AccessSession find(UUID id) {
        return sessions.findById(id).orElseThrow(NotFoundException::new);
    }

    @Override
    public Page<SessionView> sessions(int page, int size) {
        Pagination.requireValid(page, size);
        return work.run(() -> {
            SessionAccess.Auth auth = access.auth();
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

    @Override
    public void revoke(UUID id) {
        work.run(() -> {
            SessionAccess.Auth auth = access.auth();
            AccessSession session = find(id);
            if (!session.getKind().equals(auth.kind()) || !session.ownerId().equals(auth.ownerId())) {
                throw new NotFoundException();
            }
            session.revoke(clock.instant());
            record(auth, "session_revoked", "session", session.getId());
            return null;
        });
    }

    @Override
    public void revokeAll() {
        work.run(() -> {
            SessionAccess.Auth auth = access.auth();
            access.revokeOwner(auth.kind(), auth.ownerId(), null);
            record(auth, "sessions_revoked", auth.kind(), auth.ownerId());
            return null;
        });
    }

    @Override
    public Tokens openCustomerSession(UUID customerAccountId, String deviceLabel) {
        return access.newCustomer(customerAccountId, deviceLabel);
    }

    private void record(SessionAccess.Auth auth, String action, String kind, UUID id) {
        audit.record(new AuditAction(auth.actor(), "identity." + action, kind, id.toString(), null, null, null));
    }
}
