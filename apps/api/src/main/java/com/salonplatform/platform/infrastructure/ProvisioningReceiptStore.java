package com.salonplatform.platform.infrastructure;

import static com.salonplatform.platform.infrastructure.PlatformTimes.time;

import com.salonplatform.shared.infrastructure.PlatformDatabase;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Idempotency receipts of operator creations ({@code provisioning_receipt}), on the platform
 * connection: named direct-SQL exception 1 of docs/engineering/backend.md, a platform table with no
 * {@code tenant_id}. Every statement joins the creating platform transaction.
 */
@Component
public class ProvisioningReceiptStore {

    /** A stored receipt: the request's keyed fingerprint, its subject and its result as JSON text. */
    public record StoredReceipt(String fingerprint, UUID subjectId, String resultJson) {}

    private final PlatformDatabase database;

    ProvisioningReceiptStore(PlatformDatabase database) {
        this.database = database;
    }

    /** Waits for, and then holds until the transaction ends, the lock of one scoped idempotency key. */
    public void lockScope(String scope) {
        database.jdbc()
                .sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))::text")
                .param(scope)
                .query(String.class)
                .single();
    }

    public Optional<StoredReceipt> find(UUID operator, String action, UUID key) {
        return database
                .jdbc()
                .sql("""
                        SELECT fingerprint, subject_id, result::text AS result FROM provisioning_receipt
                         WHERE operator_id = ? AND action = ? AND idempotency_key = ?
                        """)
                .params(operator, action, key)
                .query((row, n) -> new StoredReceipt(
                        row.getString("fingerprint"), row.getObject("subject_id", UUID.class), row.getString("result")))
                .list()
                .stream()
                .findFirst();
    }

    public void add(
            UUID operator, String action, UUID key, String fingerprint, UUID subject, String resultJson, Instant at) {
        database.jdbc()
                .sql("""
                        INSERT INTO provisioning_receipt (operator_id, action, idempotency_key, fingerprint, subject_id, result,
                            created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                        """)
                .params(operator, action, key, fingerprint, subject, resultJson, time(at), time(at))
                .update();
    }
}
