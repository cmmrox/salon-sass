package com.salonplatform.platform.application;

import com.salonplatform.identity.inprocess.IdentityLifecycle;
import com.salonplatform.platform.domain.LifecycleTerms;
import com.salonplatform.platform.domain.OperatorPermission;
import com.salonplatform.platform.infrastructure.PlatformAudit;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import com.salonplatform.shared.outbox.OutboxLifecycle;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.shared.web.NotFoundException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Suspending, reactivating and closing a salon (PLT-06; docs/architecture/operator-console.md,
 * "Suspension, closure, export and retention"). Each action is one platform transaction holding the
 * salon's registry row lock: the state change, the lifecycle event and the platform audit commit
 * together, or nothing does. A suspended salon answers {@code salon_suspended} on its next request;
 * its sessions are refused, not revoked, and billing goes on. Reactivation restores the access state
 * billing implies at that instant. Closure is irreversible: the salon is unknown on its next request,
 * its sessions are revoked and its undelivered messages cancelled (through identity's and the outbox's
 * guarded functions), its export is scheduled and its deletion falls due 30 days later.
 */
@Service
public class SalonLifecycle {

    static final String ACTIVE = "active";
    static final String SUSPENDED = "suspended";
    static final String CLOSED = "closed";

    public record ChangeInput(Long version, String reason) {}

    public record ClosureInput(Long version, String confirmSlug, String reason) {}

    @Schema(requiredProperties = {"kind", "id", "displayName"})
    public record EventActor(
            @Schema(allowableValues = {"operator", "system"})
            String kind,

            String id,

            @Schema(
                    types = {"string", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String displayName) {}

    @Schema(requiredProperties = {"id", "kind", "reason", "actor", "at"})
    public record LifecycleEvent(
            UUID id,

            @Schema(allowableValues = {"suspended", "reactivated", "closed", "deleted"})
            String kind,

            @Schema(
                    types = {"string", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String reason,

            EventActor actor,
            Instant at) {}

    @Schema(requiredProperties = {"items", "page", "size", "total"})
    public record LifecyclePage(List<LifecycleEvent> items, int page, int size, long total) {}

    private record Locked(UUID id, String slug, String status, long version, String ownerEmail) {}

    private final PlatformDatabase database;
    private final OperatorWork work;
    private final OperatorAccess access;
    private final PlatformAudit audit;
    private final SalonProvisioning salons;
    private final BillingLedger ledger;
    private final SubscriptionStates states;
    private final IdentityLifecycle identity;
    private final OutboxLifecycle outbox;
    private final Clock clock;

    public SalonLifecycle(
            PlatformDatabase database,
            OperatorWork work,
            OperatorAccess access,
            PlatformAudit audit,
            SalonProvisioning salons,
            BillingLedger ledger,
            SubscriptionStates states,
            IdentityLifecycle identity,
            OutboxLifecycle outbox,
            Clock clock) {
        this.database = database;
        this.work = work;
        this.access = access;
        this.audit = audit;
        this.salons = salons;
        this.ledger = ledger;
        this.states = states;
        this.identity = identity;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Suspends an active salon (whatever its billing state). */
    public SalonProvisioning.SalonDetail suspend(UUID salonId, ChangeInput input) {
        List<FieldProblem> errors = new ArrayList<>();
        long version = version(input.version(), errors);
        String reason = reason(input.reason(), errors);
        work.run(() -> {
            var operator = access.require(OperatorPermission.SALON_SUSPEND);
            refuse(errors);
            Locked salon = lock(salonId);
            if (!ACTIVE.equals(salon.status())) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            current(salon, version);
            Instant now = clock.instant();
            database.jdbc().update("""
                    UPDATE tenant SET status = 'suspended', version = version + 1, updated_at = ? WHERE id = ?
                    """, Timestamp.from(now), salonId);
            event(
                    salonId,
                    SUSPENDED,
                    reason,
                    PlatformAudit.OPERATOR,
                    operator.id().toString(),
                    now);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reason", reason);
            details.put("from", ACTIVE);
            details.put("to", SUSPENDED);
            audit.record(
                    PlatformAudit.OPERATOR,
                    operator.id().toString(),
                    "salon.suspended",
                    "salon",
                    salonId.toString(),
                    details,
                    now);
            return null;
        });
        return salons.get(salonId);
    }

    /**
     * Reactivates a suspended salon: it is active again, with the access state its unpaid invoices imply
     * now ({@code read_only} when one is 14 or more days overdue), so its unexpired sessions work again.
     */
    public SalonProvisioning.SalonDetail reactivate(UUID salonId, ChangeInput input) {
        List<FieldProblem> errors = new ArrayList<>();
        long version = version(input.version(), errors);
        String reason = reason(input.reason(), errors);
        work.run(() -> {
            var operator = access.require(OperatorPermission.SALON_SUSPEND);
            refuse(errors);
            Locked salon = lock(salonId);
            if (!SUSPENDED.equals(salon.status())) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            current(salon, version);
            Instant now = clock.instant();
            database.jdbc().update("""
                    UPDATE tenant SET status = 'active', version = version + 1, updated_at = ? WHERE id = ?
                    """, Timestamp.from(now), salonId);
            // Billing went on while suspended; this brings the access state to what it implies at this
            // instant, even if the billing job has not run since a boundary passed.
            ledger.subscriptionOf(salonId, true)
                    .ifPresent(subscription -> states.applyState(
                            subscription, now, PlatformAudit.SYSTEM, "billing", BillingLedger.Changes.none()));
            String access = database.jdbc()
                    .queryForObject("SELECT access_state FROM tenant WHERE id = ?", String.class, salonId);
            event(
                    salonId,
                    "reactivated",
                    reason,
                    PlatformAudit.OPERATOR,
                    operator.id().toString(),
                    now);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reason", reason);
            details.put("from", SUSPENDED);
            details.put("to", ACTIVE);
            details.put("accessState", access);
            audit.record(
                    PlatformAudit.OPERATOR,
                    operator.id().toString(),
                    "salon.reactivated",
                    "salon",
                    salonId.toString(),
                    details,
                    now);
            return null;
        });
        return salons.get(salonId);
    }

    /**
     * Closes an active or suspended salon for good, once the exact slug is typed to confirm. In the same
     * transaction: its sessions are revoked, its undelivered messages cancelled and its export scheduled.
     */
    public SalonProvisioning.SalonDetail close(UUID salonId, ClosureInput input) {
        List<FieldProblem> errors = new ArrayList<>();
        long version = version(input.version(), errors);
        String reason = reason(input.reason(), errors);
        if (input.confirmSlug() == null || input.confirmSlug().isEmpty()) {
            errors.add(new FieldProblem("confirmSlug", "must be the salon's web address (slug), typed exactly"));
        }
        work.run(() -> {
            var operator = access.require(OperatorPermission.SALON_CLOSE);
            refuse(errors);
            Locked salon = lock(salonId);
            if (!ACTIVE.equals(salon.status()) && !SUSPENDED.equals(salon.status())) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            current(salon, version);
            if (!salon.slug().equals(input.confirmSlug())) {
                throw new ApplicationProblemException(
                        400,
                        ErrorCode.BAD_REQUEST,
                        List.of(new FieldProblem(
                                "confirmSlug", "must be the salon's web address (slug), typed exactly")));
            }
            // Millisecond precision, so the deletion instant (exactly 30 days later) can be reached exactly
            // with a clock or a test that counts in milliseconds.
            Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            Instant deleteAt = LifecycleTerms.deleteAt(now);
            database.jdbc().update("""
                    UPDATE tenant SET status = 'closed', closed_at = ?, delete_at = ?, version = version + 1,
                        updated_at = ?
                     WHERE id = ?
                    """, Timestamp.from(now), Timestamp.from(deleteAt), Timestamp.from(now), salonId);
            int sessions = identity.revokeClosedSalonSessions(salonId, now);
            int messages = outbox.cancelClosedSalon(salonId, now);
            UUID exportId = UUID.randomUUID();
            database.jdbc()
                    .update("""
                    INSERT INTO salon_export (id, salon_id, status, delete_at, created_at, updated_at)
                    VALUES (?, ?, 'pending', ?, ?, ?)
                    """, exportId, salonId, Timestamp.from(deleteAt), Timestamp.from(now), Timestamp.from(now));
            event(salonId, CLOSED, reason, PlatformAudit.OPERATOR, operator.id().toString(), now);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reason", reason);
            details.put("from", salon.status());
            details.put("to", CLOSED);
            details.put("deleteAt", deleteAt.toString());
            details.put("sessionsRevoked", sessions);
            details.put("messagesCancelled", messages);
            audit.record(
                    PlatformAudit.OPERATOR,
                    operator.id().toString(),
                    "salon.closed",
                    "salon",
                    salonId.toString(),
                    details,
                    now);
            audit.record(
                    PlatformAudit.OPERATOR,
                    operator.id().toString(),
                    "export.created",
                    "salon",
                    salonId.toString(),
                    Map.of("exportId", exportId.toString(), "deleteAt", deleteAt.toString()),
                    now);
            return null;
        });
        return salons.get(salonId);
    }

    /** The salon's lifecycle history, newest first. */
    public LifecyclePage history(UUID salonId, Paging paging) {
        return work.run(() -> {
            access.require(OperatorPermission.SALON_READ);
            Integer exists =
                    database.jdbc().queryForObject("SELECT count(*) FROM tenant WHERE id = ?", Integer.class, salonId);
            if (exists == null || exists == 0) {
                throw new NotFoundException();
            }
            List<LifecycleEvent> items = database.jdbc()
                    .query(
                            """
                    SELECT e.id, e.kind, e.reason, e.actor_kind, e.actor_id, e.created_at, u.display_name
                      FROM salon_lifecycle_event e
                      LEFT JOIN operator_user u ON e.actor_kind = 'operator' AND u.id::text = e.actor_id
                     WHERE e.salon_id = ?
                     ORDER BY e.created_at DESC, e.id
                     LIMIT ? OFFSET ?
                    """,
                            (row, n) -> new LifecycleEvent(
                                    row.getObject("id", UUID.class),
                                    row.getString("kind"),
                                    row.getString("reason"),
                                    new EventActor(
                                            row.getString("actor_kind"),
                                            row.getString("actor_id"),
                                            row.getString("display_name")),
                                    row.getTimestamp("created_at").toInstant()),
                            salonId,
                            paging.size(),
                            paging.offset());
            Long total = database.jdbc()
                    .queryForObject(
                            "SELECT count(*) FROM salon_lifecycle_event WHERE salon_id = ?", Long.class, salonId);
            return new LifecyclePage(items, paging.page(), paging.size(), total == null ? 0 : total);
        });
    }

    /** Records a lifecycle event in the caller's platform transaction. */
    void event(UUID salonId, String kind, String reason, String actorKind, String actorId, Instant at) {
        database.jdbc()
                .update(
                        """
                INSERT INTO salon_lifecycle_event (id, salon_id, kind, reason, actor_kind, actor_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                        UUID.randomUUID(),
                        salonId,
                        kind,
                        reason,
                        actorKind,
                        actorId,
                        Timestamp.from(at),
                        Timestamp.from(at));
    }

    private Locked lock(UUID salonId) {
        return database
                .jdbc()
                .query(
                        """
                SELECT id, slug, status, version, owner_email FROM tenant WHERE id = ? FOR UPDATE
                """,
                        (row, n) -> new Locked(
                                row.getObject("id", UUID.class),
                                row.getString("slug"),
                                row.getString("status"),
                                row.getLong("version"),
                                row.getString("owner_email")),
                        salonId)
                .stream()
                .findFirst()
                .orElseThrow(NotFoundException::new);
    }

    private static void current(Locked salon, long version) {
        if (salon.version() != version) {
            throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
        }
    }

    private static long version(Long version, List<FieldProblem> errors) {
        if (version == null || version < 0) {
            errors.add(new FieldProblem("version", "must be the salon's current version"));
            return -1;
        }
        return version;
    }

    private static String reason(String value, List<FieldProblem> errors) {
        String reason = LifecycleTerms.reason(value);
        if (reason == null) {
            errors.add(new FieldProblem(
                    "reason",
                    "must be plain text of " + LifecycleTerms.REASON_MIN + " to " + LifecycleTerms.REASON_MAX
                            + " characters"));
        }
        return reason;
    }

    private static void refuse(List<FieldProblem> errors) {
        if (!errors.isEmpty()) {
            List<FieldProblem> sorted = new ArrayList<>(errors);
            sorted.sort(java.util.Comparator.comparing(FieldProblem::field));
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, sorted);
        }
    }
}
