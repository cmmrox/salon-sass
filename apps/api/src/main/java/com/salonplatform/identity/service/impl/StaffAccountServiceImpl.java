package com.salonplatform.identity.service.impl;

import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.identity.domain.SessionKind;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.identity.dto.Page;
import com.salonplatform.identity.dto.SeedAccount;
import com.salonplatform.identity.dto.StaffView;
import com.salonplatform.identity.entity.StaffAccount;
import com.salonplatform.identity.inprocess.StaffSeats;
import com.salonplatform.identity.inprocess.TeamMemberSeats;
import com.salonplatform.identity.repository.StaffAccountRepository;
import com.salonplatform.identity.repository.SyntheticIdentityReset;
import com.salonplatform.identity.service.IdentityWork;
import com.salonplatform.identity.service.StaffAccountService;
import com.salonplatform.shared.audit.Actor;
import com.salonplatform.shared.audit.ActorKind;
import com.salonplatform.shared.audit.AuditAction;
import com.salonplatform.shared.audit.AuditTrail;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.NotFoundException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class StaffAccountServiceImpl implements StaffAccountService {

    private static final Sort OLDEST_FIRST = Sort.by("createdAt", "id");

    private final IdentityWork work;
    private final StaffAccountRepository accounts;
    private final SessionAccess sessions;
    private final StaffAuthority authority;
    private final StaffSeats seats;
    private final ObjectProvider<TeamMemberSeats> teamMembers;
    private final SyntheticIdentityReset syntheticReset;
    private final IdentityCrypto crypto;
    private final Clock clock;
    private final AuditTrail audit;

    @Override
    public StaffView create(StaffInput input) {
        return work.run(() -> {
            StaffAuthority.SignedIn current = authority.permitted("staff.manage");
            StaffAccount account = createAccount(input, true, null);
            record(current.account(), "staff_created", account, null, accessValues(account));
            accounts.flush();
            return view(account);
        });
    }

    @Override
    public Page<StaffView> list(int page, int size) {
        Pagination.requireValid(page, size);
        return work.run(() -> {
            authority.requireReadable("staff.list");
            org.springframework.data.domain.Page<StaffAccount> rows =
                    accounts.findAll(PageRequest.of(page, size, OLDEST_FIRST));
            return new Page<>(
                    rows.stream().map(StaffAccountServiceImpl::view).toList(), page, size, rows.getTotalElements());
        });
    }

    @Override
    public StaffView change(UUID id, StaffChange change) {
        return work.run(() -> {
            StaffAuthority.SignedIn actor = authority.permitted("staff.manage");
            StaffAccount account = account(id);
            if (account.getVersion() != change.version() || !account.isActive()) {
                throw IdentityProblems.conflict();
            }
            StaffRole next = change.role() == null ? account.getRole() : StaffValidation.role(change.role());
            boolean grant =
                    change.professionalGrant() == null ? account.isProfessionalGrant() : change.professionalGrant();
            requireGrantOnlyForAProfessional(grant, next);
            protectLastOwner(account, next, false);
            Map<String, Object> before = accessValues(account);
            boolean promotedToOwner = next == StaffRole.OWNER && account.getRole() != StaffRole.OWNER;
            account.changeAccess(next, grant, clock.instant());
            if (promotedToOwner) {
                sessions.revokeOwner(SessionKind.STAFF, account.getId(), null);
            }
            record(actor.account(), "staff_changed", account, before, accessValues(account));
            accounts.flush();
            return view(account);
        });
    }

    @Override
    public void remove(UUID id) {
        work.run(() -> {
            StaffAuthority.SignedIn actor = authority.permitted("staff.manage");
            StaffAccount account = account(id);
            protectLastOwner(account, account.getRole(), true);
            Map<String, Object> before = accessValues(account);
            account.deactivate(clock.instant());
            sessions.revokeOwner(SessionKind.STAFF, account.getId(), null);
            record(actor.account(), "staff_removed", account, before, accessValues(account));
            // The member stays on the team with no sign-in, in this transaction (F06-S01 AC-5, decision 100).
            teamMembers.ifAvailable(
                    team -> team.accountRemoved(account.getId(), actor.account().getId(), clock.instant()));
            return null;
        });
    }

    @Override
    public List<StaffView> seed(List<SeedAccount> inputs, String developmentPassword) {
        return work.run(() -> {
            SyntheticSalons.requireCurrentIsSynthetic();
            List<StaffView> seeded = new ArrayList<>();
            for (SeedAccount input : inputs) {
                String email = StaffValidation.email(input.email());
                String mobile = StaffValidation.mobile(input.mobile());
                StaffAccount account =
                        accounts.findHolding(email, mobile).stream().findFirst().orElse(null);
                if (account == null) {
                    account = createAccount(
                            new StaffInput(
                                    input.email(),
                                    input.mobile(),
                                    input.password(),
                                    input.role(),
                                    input.professionalGrant()),
                            false,
                            developmentPassword);
                    if (input.totpSecret() != null) {
                        seedSecret(account, input.totpSecret());
                    }
                }
                seeded.add(view(account));
            }
            accounts.flush();
            return List.copyOf(seeded);
        });
    }

    @Override
    public void reset() {
        work.run(() -> {
            SyntheticSalons.requireCurrentIsSynthetic();
            teamMembers.ifAvailable(team ->
                    accounts.findAll().forEach(account -> team.accountRemoved(account.getId(), null, clock.instant())));
            syntheticReset.removeEveryRecord();
            return null;
        });
    }

    /**
     * @param developmentPassword the one password below the policy that the local seed also accepts (decision
     *     105); {@code null} on every product path
     */
    private StaffAccount createAccount(StaffInput input, boolean enforceSeats, String developmentPassword) {
        String email = StaffValidation.email(input.email());
        String mobile = StaffValidation.mobile(input.mobile());
        if (email == null && mobile == null) {
            throw IdentityProblems.invalidField("email", "email or mobile is required");
        }
        StaffRole role = StaffValidation.role(input.role());
        boolean grant = Boolean.TRUE.equals(input.professionalGrant());
        requireGrantOnlyForAProfessional(grant, role);
        if (developmentPassword == null || !developmentPassword.equals(input.password())) {
            StaffValidation.requireAcceptablePassword(input.password(), "password");
        }
        if (accounts.countHolding(email, mobile) > 0) {
            throw IdentityProblems.conflict();
        }
        if (enforceSeats) {
            // Inside the salon's identity transaction, which holds its lock: of two simultaneous requests for the
            // last seat, whether creating an account or adding a team member, exactly one succeeds.
            seats.requireFreeSeat();
        }
        return accounts.persist(StaffAccount.create(
                email, mobile, crypto.encodePassword(input.password()), role, grant, clock.instant()));
    }

    /** A seeded authenticator is part of the account as it is created. */
    private void seedSecret(StaffAccount account, String totpSecret) {
        if (!totpSecret.matches("[A-Z2-7]{32}")) {
            throw IdentityProblems.invalidField("totpSecret", "must be a 20-byte base32 secret");
        }
        account.enrollMfa(
                crypto.encrypt("totp:" + TenantContext.require().id() + ":" + account.getId(), totpSecret),
                account.getCreatedAt());
    }

    private static void requireGrantOnlyForAProfessional(boolean grant, StaffRole role) {
        if (grant && role != StaffRole.PROFESSIONAL) {
            throw IdentityProblems.invalidField("professionalGrant", "applies only to a Professional");
        }
    }

    /** The salon always keeps one active Owner: the last one is neither demoted nor removed. */
    private void protectLastOwner(StaffAccount account, StaffRole next, boolean removing) {
        if (account.isActive()
                && account.getRole() == StaffRole.OWNER
                && (removing || next != StaffRole.OWNER)
                && accounts.countByActiveTrueAndRole(StaffRole.OWNER) <= 1) {
            throw IdentityProblems.conflict();
        }
    }

    private StaffAccount account(UUID id) {
        return accounts.findById(id).orElseThrow(NotFoundException::new);
    }

    private static StaffView view(StaffAccount account) {
        return new StaffView(
                account.getId(),
                account.getEmail(),
                account.getMobile(),
                account.getRole().name(),
                account.isActive(),
                account.isProfessionalGrant(),
                account.hasMfa(),
                account.getVersion());
    }

    private static Map<String, Object> accessValues(StaffAccount account) {
        return Map.of(
                "role",
                account.getRole().name(),
                "professionalGrant",
                account.isProfessionalGrant(),
                "active",
                account.isActive());
    }

    private void record(
            StaffAccount actor,
            String action,
            StaffAccount subject,
            Map<String, Object> before,
            Map<String, Object> after) {
        audit.record(new AuditAction(
                new Actor(ActorKind.STAFF, actor.getId().toString()),
                "identity." + action,
                "staff",
                subject.getId().toString(),
                null,
                before,
                after));
    }
}
