package com.salonplatform.platform.application;

import com.salonplatform.platform.application.SupportSessionRecords.Row;
import com.salonplatform.platform.infrastructure.PlatformAudit;
import com.salonplatform.platform.infrastructure.SupportCrypto;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import com.salonplatform.shared.tenancy.RequestSupport;
import com.salonplatform.shared.tenancy.SupportCredentials;
import com.salonplatform.shared.tenancy.SupportGrant;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.NotFoundException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * The support session itself, on the salon's host (PLT-07; docs/architecture/operator-console.md,
 * "Support sessions"): an entry code is exchanged once for the read-only support credential, the
 * session can end itself, and every request's credential is checked against the stored session, so an
 * end takes effect on the next request.
 */
@Service
public class SupportSessionEntry implements SupportCredentials {

    @Schema(requiredProperties = {"accessToken", "sessionId", "issuedAt", "expiresAt"})
    public record SupportCredential(String accessToken, UUID sessionId, Instant issuedAt, Instant expiresAt) {}

    private final PlatformDatabase database;
    private final OperatorWork work;
    private final SupportSessionRecords records;
    private final SupportCrypto crypto;
    private final Clock clock;

    public SupportSessionEntry(
            PlatformDatabase database,
            OperatorWork work,
            SupportSessionRecords records,
            SupportCrypto crypto,
            Clock clock) {
        this.database = database;
        this.work = work;
        this.records = records;
        this.crypto = crypto;
        this.clock = clock;
    }

    /**
     * Exchanges an entry code for the support credential: once, within its 60 seconds, on the host of
     * the salon it was issued for, while its session is open. Anything else is 401 {@code invalid_code}
     * and issues nothing.
     */
    public SupportCredential enter(String code) {
        UUID salonId = TenantContext.require().id();
        if (!SupportCrypto.codeShaped(code)) {
            throw new ApplicationProblemException(401, ErrorCode.INVALID_CODE);
        }
        SupportCredential credential = database.inTransaction(status -> {
            Instant now = clock.instant();
            Timestamp at = Timestamp.from(now);
            // Consumed in one statement: of simultaneous exchanges exactly one updates the row.
            List<Object[]> consumed = database.jdbcTemplate()
                    .query(
                            """
                    UPDATE support_entry_code c SET consumed_at = ?, updated_at = ?
                      FROM support_session s
                     WHERE c.code_hash = ? AND c.consumed_at IS NULL AND c.expires_at > ?
                       AND s.id = c.session_id AND s.salon_id = ? AND s.ended_at IS NULL AND s.expires_at > ?
                    RETURNING s.id, s.operator_id, s.expires_at
                    """,
                            (row, n) -> new Object[] {
                                row.getObject(1, UUID.class),
                                row.getObject(2, UUID.class),
                                row.getTimestamp(3).toInstant()
                            },
                            at,
                            at,
                            SupportCrypto.codeHash(code),
                            at,
                            salonId,
                            at);
            if (consumed.isEmpty()) {
                return null;
            }
            UUID sessionId = (UUID) consumed.get(0)[0];
            UUID operatorId = (UUID) consumed.get(0)[1];
            Instant expiresAt = (Instant) consumed.get(0)[2];
            if (!records.operatorActive(operatorId)) {
                status.setRollbackOnly();
                return null;
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("expiresAt", expiresAt.toString());
            records.salonAudit(
                    salonId, "support", sessionId.toString(), "support.session_entered", sessionId, null, after, now);
            Instant issued = now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            return new SupportCredential(
                    crypto.credential(salonId, sessionId, operatorId, issued, expiresAt), sessionId, issued, expiresAt);
        });
        if (credential == null) {
            throw new ApplicationProblemException(401, ErrorCode.INVALID_CODE);
        }
        return credential;
    }

    /** The support session ends itself (the operator's End session in the workspace). */
    public void endCurrent() {
        SupportGrant grant = RequestSupport.current()
                .orElseThrow(() -> new ApplicationProblemException(401, ErrorCode.INVALID_SESSION));
        work.run(() -> {
            Row row = records.lockRow(grant.sessionId())
                    .filter(each -> each.salonId().equals(grant.salonId()))
                    .orElseThrow(() -> new ApplicationProblemException(401, ErrorCode.INVALID_SESSION));
            if (!row.open(clock.instant())) {
                throw new ApplicationProblemException(401, ErrorCode.INVALID_SESSION);
            }
            records.finish(
                    row,
                    "operator",
                    grant.operatorId().toString(),
                    PlatformAudit.OPERATOR,
                    grant.operatorId().toString(),
                    "support",
                    row.id().toString());
            return null;
        });
    }

    @Override
    public Optional<SupportGrant> verify(String credential, Supplier<UUID> salon) {
        Optional<SupportCrypto.Claims> claims = crypto.claims(credential);
        if (claims.isEmpty()) {
            return Optional.empty();
        }
        UUID salonId = salon.get();
        SupportCrypto.Claims verified = claims.get();
        if (!verified.salonId().equals(salonId)) {
            throw new NotFoundException();
        }
        if (!crypto.current(verified)) {
            throw new ApplicationProblemException(401, ErrorCode.INVALID_SESSION);
        }
        // The stored session decides: its exact expiry, and whether it is still open and its operator active.
        Row live = database.inTransaction(status -> {
            Instant now = clock.instant();
            return records.find(verified.sessionId())
                    .filter(row -> row.salonId().equals(salonId)
                            && row.operatorId().equals(verified.operatorId())
                            && row.open(now)
                            && records.operatorActive(row.operatorId()))
                    .orElse(null);
        });
        if (live == null) {
            throw new ApplicationProblemException(401, ErrorCode.INVALID_SESSION);
        }
        return Optional.of(new SupportGrant(verified.sessionId(), salonId, verified.operatorId(), live.expiresAt()));
    }
}
