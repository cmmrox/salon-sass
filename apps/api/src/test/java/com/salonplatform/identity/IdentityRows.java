package com.salonplatform.identity;

import com.salonplatform.testing.TestSalons.TestSalon;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One row in every identity table for a salon, written as the owning role, which row-level security does not
 * hold back: a staff Owner with a session, a spent refresh credential, a second-step challenge and a used step;
 * a customer with a session and a code; a rate bucket; and the salon's lock row. The isolation tests give two
 * salons these rows and check that one salon reaches none of the other's.
 */
final class IdentityRows {

    /** Every identity table, dependants first, so that the list also deletes in order. */
    static final List<String> TABLES = List.of(
            "identity_refresh_history",
            "identity_mfa_step",
            "identity_mfa_challenge",
            "identity_customer_challenge",
            "identity_rate_bucket",
            "identity_session",
            "identity_guard",
            "identity_customer",
            "identity_staff");

    private final JdbcTemplate owner;

    IdentityRows(JdbcTemplate owner) {
        this.owner = owner;
    }

    /** The salon's identity rows and the values that find them. */
    record SalonRows(
            UUID salon,
            UUID staff,
            String email,
            String mobile,
            UUID customer,
            String customerMobile,
            UUID staffSession,
            String staffRefreshHash,
            UUID customerSession,
            UUID refresh,
            String spentRefreshHash,
            UUID mfaChallenge,
            UUID mfaStep,
            long step,
            UUID rateBucket,
            String keyHash,
            UUID customerChallenge,
            String numberHash) {}

    SalonRows writeFor(TestSalon salon) {
        String unique = Long.toString(ThreadLocalRandom.current().nextLong(10_000_000L, 100_000_000L));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Timestamp created = Timestamp.from(now);
        Timestamp later = Timestamp.from(now.plus(1, ChronoUnit.DAYS));
        SalonRows rows = new SalonRows(
                salon.id(),
                UUID.randomUUID(),
                "owner-" + unique + "@example.test",
                "+9477" + unique.substring(1),
                UUID.randomUUID(),
                "+9471" + unique.substring(1),
                UUID.randomUUID(),
                "staff-refresh-" + unique,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "spent-refresh-" + unique,
                UUID.randomUUID(),
                UUID.randomUUID(),
                Long.parseLong(unique),
                UUID.randomUUID(),
                "key-" + unique,
                UUID.randomUUID(),
                "number-" + unique);
        owner.update("""
                INSERT INTO identity_staff (id, tenant_id, created_at, updated_at, email, mobile, password_hash, role,
                    active, professional_grant, mfa_secret, version)
                VALUES (?, ?, ?, ?, ?, ?, 'not-a-real-hash', 'OWNER', true, false, NULL, 0)
                """, rows.staff(), salon.id(), created, created, rows.email(), rows.mobile());
        owner.update("""
                INSERT INTO identity_customer (id, tenant_id, mobile, name, active, created_at, updated_at, version)
                VALUES (?, ?, ?, 'Customer', true, ?, ?, 0)
                """, rows.customer(), salon.id(), rows.customerMobile(), created, created);
        owner.update(
                """
                INSERT INTO identity_session (id, tenant_id, created_at, updated_at, account_id, kind, refresh_hash,
                    device_label, expires_at, generation, mfa_verified)
                VALUES (?, ?, ?, ?, ?, 'staff', ?, 'Front desk', ?, 1, true)
                """, rows.staffSession(), salon.id(), created, created, rows.staff(), rows.staffRefreshHash(), later);
        owner.update(
                """
                INSERT INTO identity_session (id, tenant_id, created_at, updated_at, customer_account_id, kind,
                    refresh_hash, device_label, expires_at, generation, mfa_verified)
                VALUES (?, ?, ?, ?, ?, 'customer', ?, 'Phone', ?, 1, false)
                """,
                rows.customerSession(),
                salon.id(),
                created,
                created,
                rows.customer(),
                "customer-refresh-" + unique,
                later);
        owner.update("""
                INSERT INTO identity_refresh_history (id, tenant_id, created_at, updated_at, session_id, credential_hash)
                VALUES (?, ?, ?, ?, ?, ?)
                """, rows.refresh(), salon.id(), created, created, rows.staffSession(), rows.spentRefreshHash());
        owner.update(
                """
                INSERT INTO identity_mfa_challenge (id, tenant_id, created_at, updated_at, account_id, session_id,
                    purpose, credential_hash, device_label, expires_at, attempts)
                VALUES (?, ?, ?, ?, ?, NULL, 'verify_mfa', ?, 'Front desk', ?, 0)
                """, rows.mfaChallenge(), salon.id(), created, created, rows.staff(), "challenge-" + unique, later);
        owner.update("""
                INSERT INTO identity_mfa_step (id, tenant_id, created_at, updated_at, account_id, step)
                VALUES (?, ?, ?, ?, ?, ?)
                """, rows.mfaStep(), salon.id(), created, created, rows.staff(), rows.step());
        owner.update("""
                INSERT INTO identity_rate_bucket (id, tenant_id, created_at, updated_at, dimension, key_hash,
                    window_start, attempts)
                VALUES (?, ?, ?, ?, 'staff-identifier', ?, ?, 1)
                """, rows.rateBucket(), salon.id(), created, created, rows.keyHash(), created);
        owner.update(
                """
                INSERT INTO identity_customer_challenge (id, tenant_id, number_hash, encrypted_recipient, code_hash,
                    device_hash, device_label, channel, message_id, expires_at, attempts, created_at, updated_at)
                VALUES (?, ?, ?, 'sealed', 'code', 'device', 'Phone', 'sms', ?, ?, 0, ?, ?)
                """,
                rows.customerChallenge(),
                salon.id(),
                rows.numberHash(),
                UUID.randomUUID(),
                later,
                created,
                created);
        owner.update(
                "INSERT INTO identity_guard (id, tenant_id, created_at, updated_at) VALUES (?, ?, ?, ?)",
                salon.id(),
                salon.id(),
                created,
                created);
        return rows;
    }

    /** Every identity row of the salon, table by table, as the owning role sees it. */
    Map<String, List<Map<String, Object>>> snapshotOf(UUID salon) {
        Map<String, List<Map<String, Object>>> snapshot = new LinkedHashMap<>();
        for (String table : TABLES) {
            snapshot.put(
                    table, owner.queryForList("SELECT * FROM " + table + " WHERE tenant_id = ? ORDER BY id", salon));
        }
        return snapshot;
    }
}
