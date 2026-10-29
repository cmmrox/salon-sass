package com.salonplatform.identity.application;

import com.salonplatform.identity.domain.SessionKind;
import com.salonplatform.identity.domain.StaffAccount;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.identity.infrastructure.IdentityCrypto;
import com.salonplatform.identity.infrastructure.StaffAccountRepository;
import com.salonplatform.identity.infrastructure.SyntheticIdentityReset;
import com.salonplatform.shared.audit.Actor;
import com.salonplatform.shared.audit.ActorKind;
import com.salonplatform.shared.audit.AuditAction;
import com.salonplatform.shared.audit.AuditTrail;
import com.salonplatform.shared.entitlements.SalonEntitlements;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.NotFoundException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * The salon's staff accounts as the Owner manages them (docs/architecture/identity-and-access.md): created within
 * the plan's staff seats, their role and Professional grant changed, removed; never the last active Owner. A
 * change names the version it was made against.
 */
@Service
@RequiredArgsConstructor
public class StaffAccounts {

    private static final Sort OLDEST_FIRST = Sort.by("createdAt", "id");

    private final IdentityWork work;
    private final StaffAccountRepository accounts;
    private final SessionAccess sessions;
    private final StaffAuthority authority;
    private final SalonEntitlements entitlements;
    private final SyntheticIdentityReset syntheticReset;
    private final IdentityCrypto crypto;
    private final Clock clock;
    private final AuditTrail audit;

    public record StaffView(
            UUID id,

            @io.swagger.v3.oas.annotations.media.Schema(
                    types = {"string", "null"},
                    requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
            String email,

            @io.swagger.v3.oas.annotations.media.Schema(
                    types = {"string", "null"},
                    requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
            String mobile,

            String role,
            boolean active,
            boolean professionalGrant,
            boolean mfaEnabled,
            long version) {}

    public record StaffInput(String email, String mobile, String password, String role, Boolean professionalGrant) {}

    public record StaffChange(long version, String role, Boolean professionalGrant) {}

    public record SeedAccount(
            String email, String mobile, String password, String role, String totpSecret, Boolean professionalGrant) {}

    public StaffView create(StaffInput input) {
        return work.run(() -> {
            StaffAuthority.SignedIn current = authority.permitted("staff.manage");
            StaffAccount account = createAccount(input, true);
            record(current.account(), "staff_created", account, null, accessValues(account));
            accounts.flush();
            return view(account);
        });
    }

    public Page<StaffView> list(int page, int size) {
        Page.requireValid(page, size);
        return work.run(() -> {
            authority.requireReadable("staff.list");
            org.springframework.data.domain.Page<StaffAccount> rows =
                    accounts.findAll(PageRequest.of(page, size, OLDEST_FIRST));
            return new Page<>(rows.stream().map(StaffAccounts::view).toList(), page, size, rows.getTotalElements());
        });
    }

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

    public void remove(UUID id) {
        work.run(() -> {
            StaffAuthority.SignedIn actor = authority.permitted("staff.manage");
            StaffAccount account = account(id);
            protectLastOwner(account, account.getRole(), true);
            Map<String, Object> before = accessValues(account);
            account.deactivate(clock.instant());
            sessions.revokeOwner(SessionKind.STAFF, account.getId(), null);
            record(actor.account(), "staff_removed", account, before, accessValues(account));
            return null;
        });
    }

    /**
     * Local fixtures only (no production route reaches this): creates each account that no account holds the
     * email or mobile of yet, with its authenticator secret when one is given.
     */
    public List<StaffView> seed(List<SeedAccount> inputs) {
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
                            false);
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

    /** Local fixtures only: removes every staff and customer record of the salon. */
    public void reset() {
        work.run(() -> {
            SyntheticSalons.requireCurrentIsSynthetic();
            syntheticReset.removeEveryRecord();
            return null;
        });
    }

    private StaffAccount createAccount(StaffInput input, boolean enforceSeats) {
        String email = StaffValidation.email(input.email());
        String mobile = StaffValidation.mobile(input.mobile());
        if (email == null && mobile == null) {
            throw IdentityProblems.invalidField("email", "email or mobile is required");
        }
        StaffRole role = StaffValidation.role(input.role());
        boolean grant = Boolean.TRUE.equals(input.professionalGrant());
        requireGrantOnlyForAProfessional(grant, role);
        StaffValidation.requireAcceptablePassword(input.password(), "password");
        if (accounts.countHolding(email, mobile) > 0) {
            throw IdentityProblems.conflict();
        }
        if (enforceSeats) {
            requireStaffSeat();
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

    /**
     * Staff seats (PLT-04): active staff accounts may not exceed the plan's limit. Called inside the salon's
     * identity transaction, which holds the salon's identity lock, so of two simultaneous creations for the last
     * seat exactly one succeeds; a salon without a plan is not limited.
     */
    private void requireStaffSeat() {
        entitlements.forCurrentSalon().ifPresent(limits -> {
            if (accounts.countByActiveTrue() >= limits.staffSeats()) {
                throw SalonEntitlements.exceeded(SalonEntitlements.STAFF_SEATS, limits.staffSeats());
            }
        });
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
