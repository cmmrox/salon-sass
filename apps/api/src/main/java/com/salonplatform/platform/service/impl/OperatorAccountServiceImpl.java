package com.salonplatform.platform.service.impl;

import com.salonplatform.identity.inprocess.IdentityProvisioning;
import com.salonplatform.platform.adapter.OperatorCrypto;
import com.salonplatform.platform.domain.OperatorRole;
import com.salonplatform.platform.domain.PlainText;
import com.salonplatform.platform.dto.AccountInput;
import com.salonplatform.platform.repository.OperatorSecurityStore;
import com.salonplatform.platform.repository.OperatorSessionStore;
import com.salonplatform.platform.repository.OperatorUserStore;
import com.salonplatform.platform.repository.OperatorUserStore.FixtureCandidate;
import com.salonplatform.platform.repository.OperatorUserStore.NewAccount;
import com.salonplatform.platform.repository.PlatformAudit;
import com.salonplatform.platform.service.OperatorAccountService;
import com.salonplatform.shared.persistence.PlatformDatabase;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class OperatorAccountServiceImpl implements OperatorAccountService {

    private final PlatformDatabase database;
    private final OperatorUserStore users;
    private final OperatorSessionStore sessions;
    private final OperatorSecurityStore security;
    private final OperatorCrypto crypto;
    private final IdentityProvisioning identity;
    private final PlatformAudit audit;
    private final Clock clock;

    OperatorAccountServiceImpl(
            PlatformDatabase database,
            OperatorUserStore users,
            OperatorSessionStore sessions,
            OperatorSecurityStore security,
            OperatorCrypto crypto,
            IdentityProvisioning identity,
            PlatformAudit audit,
            Clock clock) {
        this.database = database;
        this.users = users;
        this.sessions = sessions;
        this.security = security;
        this.crypto = crypto;
        this.identity = identity;
        this.audit = audit;
        this.clock = clock;
    }

    private record Valid(String email, String displayName, OperatorRole role, String passwordHash, String totpSecret) {

        NewAccount stored() {
            return new NewAccount(email, displayName, passwordHash, role.name());
        }
    }

    private Valid validate(AccountInput input) {
        List<FieldProblem> errors = new ArrayList<>();
        String email = null;
        try {
            email = identity.normalizedEmail(input.email());
        } catch (IllegalArgumentException ex) {
            email = null;
        }
        if (email == null) {
            errors.add(new FieldProblem("email", "must be a valid email address"));
        }
        String name = PlainText.line(input.displayName(), 120);
        if (name == null) {
            errors.add(new FieldProblem("displayName", "must be plain text between 1 and 120 characters"));
        }
        OperatorRole role = OperatorRole.of(input.role()).orElse(null);
        if (role == null) {
            errors.add(new FieldProblem("role", "must be OPERATOR_ADMIN or OPERATOR_SUPPORT"));
        }
        if (!identity.acceptablePassword(input.password())) {
            errors.add(new FieldProblem(
                    "password", "must contain 15 to 128 characters and must not be a common password"));
        }
        if (input.totpSecret() != null && !input.totpSecret().matches("[A-Z2-7]{32}")) {
            errors.add(new FieldProblem("totpSecret", "must be a 20-byte base32 secret"));
        }
        if (!errors.isEmpty()) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, errors);
        }
        return new Valid(email, name, role, crypto.encodePassword(input.password()), input.totpSecret());
    }

    @Override
    public boolean bootstrap(AccountInput input) {
        Valid account =
                validate(new AccountInput(input.email(), input.displayName(), input.role(), input.password(), null));
        return database.inTransaction(status -> {
            Instant now = clock.instant();
            UUID id = UUID.randomUUID();
            boolean created = users.createUnlessTaken(id, account.stored(), now);
            if (created) {
                audit.record(
                        PlatformAudit.SYSTEM,
                        "operator-bootstrap",
                        "operator.bootstrapped",
                        "operator",
                        id.toString(),
                        java.util.Map.of("role", account.role().name()),
                        now);
            }
            return created;
        });
    }

    @Override
    public int seed(List<AccountInput> inputs) {
        List<Valid> accounts = inputs.stream().map(this::validate).toList();
        return database.inTransaction(status -> {
            Instant now = clock.instant();
            for (Valid account : accounts) {
                Optional<FixtureCandidate> existing = users.findFixtureCandidate(account.email());
                if (existing.isPresent() && !existing.get().fixture()) {
                    throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
                }
                UUID id = existing.map(FixtureCandidate::id).orElse(null);
                if (id == null) {
                    id = UUID.randomUUID();
                    users.createFixture(id, account.stored(), now);
                }
                String secret = account.totpSecret() == null
                        ? null
                        : crypto.encrypt("operator-totp:" + id, account.totpSecret());
                users.resetFixture(id, account.stored(), secret, now);
            }
            return accounts.size();
        });
    }

    @Override
    public void resetFixtures() {
        database.inTransaction(status -> {
            Instant now = clock.instant();
            List<String> emails = users.fixtureEmails();
            security.clearFixtureChallenges();
            sessions.revokeFixtureSessions(now);
            for (String email : emails) {
                security.clearEmailCounter(crypto.hash("rate-operator-email", email.toLowerCase(Locale.ROOT)));
            }
            security.clearAddressCounters();
            return null;
        });
    }
}
