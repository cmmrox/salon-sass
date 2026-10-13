package com.salonplatform.shared;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * AC-13: the two new tenant tables, as the application's role, with row-level security alone (no
 * Hibernate in the path): a transaction with no salon set sees nothing, one set to a salon sees only
 * that salon's rows and can write no other salon's, and an audit entry cannot be changed by anyone.
 */
class TenantTablesIsolationIntegrationTest {

    private static final String[] TABLES = { "audit_entry", "outbox_message" };

    private static TestDatabase database;

    private static TestSalon alpha;

    private static TestSalon beta;

    @BeforeAll
    static void twoSalonsWithARowEachInEachTable() {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        try (var migrated = database.start()) {
            // Migrated.
        }
        JdbcTemplate owner = database.asOwner();
        TestSalons salons = new TestSalons(owner);
        alpha = salons.create("Isolation Alpha", "#111111");
        beta = salons.create("Isolation Beta", "#222222");
        for (TestSalon salon : new TestSalon[] { alpha, beta }) {
            owner.update("INSERT INTO audit_entry (tenant_id, id, actor_kind, actor_id, action, subject_kind, subject_id, "
                    + "created_at, updated_at) VALUES (?, ?, 'system', 'test', 'x.y', 'thing', ?, now(), now())",
                    salon.id(), UUID.randomUUID(), salon.slug());
            owner.update("INSERT INTO outbox_message (tenant_id, id, channel, payload, status, next_attempt_at, created_at, "
                    + "updated_at) VALUES (?, ?, 'probe', '{}', 'pending', now(), now(), now())", salon.id(), UUID.randomUUID());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "audit_entry", "outbox_message" })
    void withNoSalonSetNothingIsVisible(String table) throws SQLException {
        try (Connection connection = database.connectAsApplication()) {
            connection.setAutoCommit(false);
            assertThat(count(connection, table)).isZero();
            connection.rollback();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "audit_entry", "outbox_message" })
    void aSalonSeesOnlyItsOwnRows(String table) throws SQLException {
        try (Connection connection = database.connectAsApplication()) {
            connection.setAutoCommit(false);
            setSalon(connection, alpha);
            try (PreparedStatement statement = connection.prepareStatement("SELECT DISTINCT tenant_id FROM " + table);
                    ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject(1, UUID.class)).isEqualTo(alpha.id());
                assertThat(rows.next()).as("no other salon's row").isFalse();
            }
            connection.rollback();
        }
    }

    @Test
    void aSalonCannotWriteARowForAnotherSalon() throws SQLException {
        for (String insert : new String[] {
            "INSERT INTO audit_entry (tenant_id, id, actor_kind, actor_id, action, subject_kind, subject_id, created_at, "
                    + "updated_at) VALUES (?, gen_random_uuid(), 'system', 't', 'x.y', 'thing', 'x', now(), now())",
            "INSERT INTO outbox_message (tenant_id, id, channel, payload, status, next_attempt_at, created_at, updated_at) "
                    + "VALUES (?, gen_random_uuid(), 'probe', '{}', 'pending', now(), now(), now())" }) {
            try (Connection connection = database.connectAsApplication()) {
                connection.setAutoCommit(false);
                setSalon(connection, alpha);
                try (PreparedStatement statement = connection.prepareStatement(insert)) {
                    statement.setObject(1, beta.id());
                    assertThatExceptionOfType(SQLException.class).isThrownBy(statement::executeUpdate)
                            .satisfies(refused -> assertThat(refused.getSQLState()).isEqualTo("42501"));
                }
                connection.rollback();
            }
        }
    }

    @Test
    void aSalonCannotChangeOrRemoveAnotherSalonsMessage() throws SQLException {
        try (Connection connection = database.connectAsApplication()) {
            connection.setAutoCommit(false);
            setSalon(connection, alpha);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE outbox_message SET last_error = 'x' WHERE tenant_id = ?");
                    PreparedStatement delete = connection.prepareStatement("DELETE FROM outbox_message WHERE tenant_id = ?")) {
                update.setObject(1, beta.id());
                delete.setObject(1, beta.id());
                assertThat(update.executeUpdate()).isZero();
                assertThat(delete.executeUpdate()).isZero();
            }
            connection.rollback();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "UPDATE audit_entry SET reason = 'changed'", "DELETE FROM audit_entry",
        "TRUNCATE audit_entry" })
    void theApplicationCannotChangeOrRemoveAnAuditEntryEvenItsOwn(String sql) throws SQLException {
        try (Connection connection = database.connectAsApplication()) {
            connection.setAutoCommit(false);
            setSalon(connection, alpha);
            try (Statement statement = connection.createStatement()) {
                assertThatExceptionOfType(SQLException.class).isThrownBy(() -> statement.execute(sql))
                        .satisfies(refused -> assertThat(refused.getSQLState()).isEqualTo("42501"));
            }
            connection.rollback();
        }
    }

    @Test
    void noRoleCanChangeAnAuditEntryNotEvenTheOwner() throws SQLException {
        try (Connection connection = database.connectAsOwner(); Statement statement = connection.createStatement()) {
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> statement.execute("UPDATE audit_entry SET reason = 'changed'"))
                    .satisfies(refused -> assertThat(refused.getMessage()).contains("never changed"));
        }
    }

    private static void setSalon(Connection connection, TestSalon salon) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            statement.setString(1, salon.id().toString());
            statement.execute();
        }
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
