package com.salonplatform.platform.application;

import com.salonplatform.platform.infrastructure.OperatorCrypto;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Idempotent creation (docs/architecture/operator-console.md, "Profile, plans and retry rules"): an
 * {@code Idempotency-Key} UUID scoped to operator, action and key. The receipt holds a keyed
 * fingerprint of the normalized request (credential-sensitive input included, never the body itself)
 * and the nonsecret result, in the creation's own transaction, so a failed transaction reserves
 * nothing. The same key with the same request returns the original result; with a changed request,
 * 409 {@code idempotency_conflict}. Simultaneous uses of one key are serialized by a transaction
 * lock, so they yield one result.
 */
@Service
public class ProvisioningReceipts {

    public static final String HEADER = "Idempotency-Key";

    public record Receipt(UUID subjectId, Map<String, Object> result) {}

    private final PlatformDatabase database;
    private final OperatorCrypto crypto;
    private final JsonMapper json;

    public ProvisioningReceipts(PlatformDatabase database, OperatorCrypto crypto, JsonMapper json) {
        this.database = database;
        this.crypto = crypto;
        this.json = json;
    }

    /**
     * The key from its header: a UUID, or 400 {@code bad_request} naming the header.
     */
    public static UUID key(String header) {
        if (header != null
                && header.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            return UUID.fromString(header);
        }
        throw new ApplicationProblemException(
                400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem(HEADER, "must be a UUID")));
    }

    /** The keyed fingerprint of a normalized request, as canonical JSON of its fields in order. */
    public String fingerprint(String action, Map<String, ?> normalized) {
        return crypto.fingerprint(action + "\n" + json.writeValueAsString(normalized));
    }

    /**
     * Inside the creating transaction: waits for any other use of the same scoped key, then answers its
     * receipt when there is one. A receipt for a different fingerprint is a conflict.
     */
    public Optional<Receipt> replay(UUID operator, String action, UUID key, String fingerprint) {
        database.requireTransaction();
        database.jdbcTemplate()
                .queryForObject(
                        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))::text",
                        String.class,
                        operator + "|" + action + "|" + key);
        return database
                .jdbcTemplate()
                .query(
                        """
                SELECT fingerprint, subject_id, result::text AS result FROM provisioning_receipt
                 WHERE operator_id = ? AND action = ? AND idempotency_key = ?
                """,
                        (row, n) -> {
                            if (!crypto.equalHash(row.getString("fingerprint"), fingerprint)) {
                                throw new ApplicationProblemException(409, ErrorCode.IDEMPOTENCY_CONFLICT);
                            }
                            // Jackson reads a raw Map; a stored result is a JSON object, so every key is a string.
                            @SuppressWarnings("unchecked")
                            Map<String, Object> result = json.readValue(row.getString("result"), Map.class);
                            return new Receipt(row.getObject("subject_id", UUID.class), result);
                        },
                        operator,
                        action,
                        key)
                .stream()
                .findFirst();
    }

    /** Stores the receipt in the creating transaction. */
    public void store(
            UUID operator,
            String action,
            UUID key,
            String fingerprint,
            UUID subject,
            Map<String, ?> result,
            Instant at) {
        database.requireTransaction();
        Timestamp time = Timestamp.from(at);
        database.jdbcTemplate()
                .update("""
                INSERT INTO provisioning_receipt (operator_id, action, idempotency_key, fingerprint, subject_id, result,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """, operator, action, key, fingerprint, subject, json.writeValueAsString(result), time, time);
    }
}
