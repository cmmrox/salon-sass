package com.salonplatform.platform.infrastructure;

import static com.salonplatform.platform.infrastructure.PlatformTimes.time;

import com.salonplatform.shared.infrastructure.PlatformDatabase;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Operator accounts ({@code operator_user}), on the platform connection: named direct-SQL exception 1
 * of docs/engineering/backend.md, a platform table with no {@code tenant_id}. Every statement joins the
 * platform transaction the calling use case opened.
 */
@Component
public class OperatorUserStore {

    /** An operator account as sign-in and authorization read it. */
    public record Account(UUID id, String email, String passwordHash, String role, boolean active, String mfaSecret) {}

    /** The account a fixture email names, and whether the local fixture hook created it. */
    public record FixtureCandidate(UUID id, boolean fixture) {}

    /** An account to write: the email normalised, the password already hashed. */
    public record NewAccount(String email, String displayName, String passwordHash, String role) {}

    private static final String ACCOUNT =
            "SELECT id, email, password_hash, role, active, mfa_secret FROM operator_user";

    private final PlatformDatabase database;

    OperatorUserStore(PlatformDatabase database) {
        this.database = database;
    }

    public Optional<Account> findByEmail(String email) {
        return findOne(ACCOUNT + " WHERE email = ?", email);
    }

    public Optional<Account> findById(UUID id) {
        return findOne(ACCOUNT + " WHERE id = ?", id);
    }

    private Optional<Account> findOne(String sql, Object key) {
        return database
                .jdbc()
                .sql(sql)
                .param(key)
                .query((row, n) -> new Account(
                        row.getObject("id", UUID.class),
                        row.getString("email"),
                        row.getString("password_hash"),
                        row.getString("role"),
                        row.getBoolean("active"),
                        row.getString("mfa_secret")))
                .list()
                .stream()
                .findFirst();
    }

    /**
     * The offline bootstrap's account, active and without an MFA secret, unless one already has the
     * email.
     *
     * @return whether the account was created
     */
    public boolean createUnlessTaken(UUID id, NewAccount account, Instant now) {
        return database.jdbc()
                        .sql("""
                        INSERT INTO operator_user (id, email, display_name, password_hash, role, active, fixture, version,
                            created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, true, false, 0, ?, ?) ON CONFLICT (email) DO NOTHING
                        """)
                        .params(
                                id,
                                account.email(),
                                account.displayName(),
                                account.passwordHash(),
                                account.role(),
                                time(now),
                                time(now))
                        .update()
                > 0;
    }

    public Optional<FixtureCandidate> findFixtureCandidate(String email) {
        return database
                .jdbc()
                .sql("SELECT id, fixture FROM operator_user WHERE email = ?")
                .param(email)
                .query((row, n) -> new FixtureCandidate(row.getObject("id", UUID.class), row.getBoolean("fixture")))
                .list()
                .stream()
                .findFirst();
    }

    public void createFixture(UUID id, NewAccount account, Instant now) {
        database.jdbc()
                .sql("""
                        INSERT INTO operator_user (id, email, display_name, password_hash, role, active, fixture,
                            version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, true, true, 0, ?, ?)
                        """)
                .params(
                        id,
                        account.email(),
                        account.displayName(),
                        account.passwordHash(),
                        account.role(),
                        time(now),
                        time(now))
                .update();
    }

    /** Brings a fixture account to the given state; an account the fixture hook did not create is untouched. */
    public void resetFixture(UUID id, NewAccount account, String mfaSecret, Instant now) {
        database.jdbc()
                .sql("""
                        UPDATE operator_user SET display_name = ?, password_hash = ?, role = ?, active = true,
                            mfa_secret = ?, version = version + 1, updated_at = ?
                         WHERE id = ? AND fixture
                        """)
                .params(account.displayName(), account.passwordHash(), account.role(), mfaSecret, time(now), id)
                .update();
    }

    public List<String> fixtureEmails() {
        return database.jdbc()
                .sql("SELECT email FROM operator_user WHERE fixture")
                .query(String.class)
                .list();
    }

    /** Saves the enrolled secret of an operator who has none yet. */
    public void saveMfaSecret(UUID id, String encryptedSecret, Instant now) {
        database.jdbc().sql("""
                        UPDATE operator_user SET mfa_secret = ?, version = version + 1, updated_at = ?
                         WHERE id = ? AND mfa_secret IS NULL
                        """).params(encryptedSecret, time(now), id).update();
    }
}
