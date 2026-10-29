package com.salonplatform.platform.application;

import com.salonplatform.identity.IdentityProvisioning;
import com.salonplatform.platform.domain.OperatorRole;
import com.salonplatform.platform.domain.PlainText;
import com.salonplatform.platform.infrastructure.OperatorCrypto;
import com.salonplatform.platform.infrastructure.PlatformAudit;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Creating operator accounts outside the console: the explicit offline bootstrap command, and the
 * local fixture hook's synthetic accounts. There is no public registration, reset or recovery, and no
 * environment creates a default operator when it starts.
 */
@Service
public class OperatorAccounts {

    /** An account to create. The password is validated with identity's policy and hashed here. */
    public record AccountInput(String email, String displayName, String role, String password, String totpSecret) {}

    private final PlatformDatabase database;
    private final OperatorCrypto crypto;
    private final IdentityProvisioning identity;
    private final PlatformAudit audit;
    private final Clock clock;

    public OperatorAccounts(
            PlatformDatabase database,
            OperatorCrypto crypto,
            IdentityProvisioning identity,
            PlatformAudit audit,
            Clock clock) {
        this.database = database;
        this.crypto = crypto;
        this.identity = identity;
        this.audit = audit;
        this.clock = clock;
    }

    private record Valid(String email, String displayName, OperatorRole role, String passwordHash, String totpSecret) {}

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

    /**
     * The offline bootstrap: creates the operator when no account has that email, with no MFA secret
     * (first sign-in enrolls). An existing account is left unchanged.
     *
     * @return whether an account was created
     */
    public boolean bootstrap(AccountInput input) {
        Valid account =
                validate(new AccountInput(input.email(), input.displayName(), input.role(), input.password(), null));
        return database.inTransaction(status -> {
            Instant now = clock.instant();
            UUID id = UUID.randomUUID();
            int created = database.jdbc()
                    .update(
                            """
                    INSERT INTO operator_user (id, email, display_name, password_hash, role, active, fixture, version,
                        created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, true, false, 0, ?, ?) ON CONFLICT (email) DO NOTHING
                    """,
                            id,
                            account.email(),
                            account.displayName(),
                            account.passwordHash(),
                            account.role().name(),
                            Timestamp.from(now),
                            Timestamp.from(now));
            if (created > 0) {
                audit.record(
                        PlatformAudit.SYSTEM,
                        "operator-bootstrap",
                        "operator.bootstrapped",
                        "operator",
                        id.toString(),
                        java.util.Map.of("role", account.role().name()),
                        now);
            }
            return created > 0;
        });
    }

    /**
     * Local fixtures only: creates each synthetic account, or brings an existing synthetic one to the
     * given state (password, name, role, and the TOTP secret or none). An account not created by this
     * hook is refused, never changed.
     *
     * @return how many accounts were seeded
     */
    public int seed(List<AccountInput> inputs) {
        List<Valid> accounts = inputs.stream().map(this::validate).toList();
        return database.inTransaction(status -> {
            Instant now = clock.instant();
            for (Valid account : accounts) {
                UUID id = database
                        .jdbc()
                        .query(
                                "SELECT id, fixture FROM operator_user WHERE email = ?",
                                (row, n) -> {
                                    if (!row.getBoolean("fixture")) {
                                        throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
                                    }
                                    return row.getObject("id", UUID.class);
                                },
                                account.email())
                        .stream()
                        .findFirst()
                        .orElse(null);
                if (id == null) {
                    id = UUID.randomUUID();
                    database.jdbc()
                            .update(
                                    """
                            INSERT INTO operator_user (id, email, display_name, password_hash, role, active, fixture,
                                version, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, true, true, 0, ?, ?)
                            """,
                                    id,
                                    account.email(),
                                    account.displayName(),
                                    account.passwordHash(),
                                    account.role().name(),
                                    Timestamp.from(now),
                                    Timestamp.from(now));
                }
                String secret = account.totpSecret() == null
                        ? null
                        : crypto.encrypt("operator-totp:" + id, account.totpSecret());
                database.jdbc()
                        .update(
                                """
                        UPDATE operator_user SET display_name = ?, password_hash = ?, role = ?, active = true,
                            mfa_secret = ?, version = version + 1, updated_at = ?
                         WHERE id = ? AND fixture
                        """,
                                account.displayName(),
                                account.passwordHash(),
                                account.role().name(),
                                secret,
                                Timestamp.from(now),
                                id);
            }
            return accounts.size();
        });
    }

    /**
     * Local fixtures only: clears the synthetic accounts' MFA challenges and consumed steps, revokes
     * their sessions, and clears their email counters and every operator address counter. Salons,
     * plans and other accounts are untouched.
     */
    public void resetFixtures() {
        database.inTransaction(status -> {
            Instant now = clock.instant();
            List<String> emails =
                    database.jdbc().queryForList("SELECT email FROM operator_user WHERE fixture", String.class);
            database.jdbc()
                    .update(
                            "DELETE FROM operator_mfa_challenge WHERE user_id IN (SELECT id FROM operator_user WHERE fixture)");
            database.jdbc()
                    .update(
                            "DELETE FROM operator_mfa_step WHERE user_id IN (SELECT id FROM operator_user WHERE fixture)");
            database.jdbc().update("""
                    UPDATE operator_session SET revoked_at = ?, updated_at = ?
                     WHERE revoked_at IS NULL AND user_id IN (SELECT id FROM operator_user WHERE fixture)
                    """, Timestamp.from(now), Timestamp.from(now));
            for (String email : emails) {
                database.jdbc()
                        .update(
                                "DELETE FROM operator_rate_bucket WHERE dimension = 'operator-email' AND key_hash = ?",
                                crypto.hash("rate-operator-email", email.toLowerCase(Locale.ROOT)));
            }
            database.jdbc().update("DELETE FROM operator_rate_bucket WHERE dimension = 'operator-address'");
            return null;
        });
    }
}
