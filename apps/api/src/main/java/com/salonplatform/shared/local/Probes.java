package com.salonplatform.shared.local;

import com.salonplatform.shared.audit.Actor;
import com.salonplatform.shared.audit.AuditAction;
import com.salonplatform.shared.audit.AuditTrail;
import com.salonplatform.shared.outbox.Outbox;
import com.salonplatform.shared.outbox.OutboxMessageView;
import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.time.SalonTime;
import java.io.Serial;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The probe's use cases: write one outbox message on the channel {@code probe} and one audit entry
 * ({@code probe.created}) in one transaction, for the salon of the request's hostname; and show a
 * probe's message, its audit entries and the provider calls it received.
 */
@Service
@Profile("local")
class Probes {

    static final String SUBJECT = "probe";

    static final String CREATED = "probe.created";

    private final Outbox outbox;

    private final AuditTrail audit;

    private final SalonTime time;

    private final ProbeProvider provider;

    private final TransactionTemplate writes;

    private final TransactionTemplate reads;

    Probes(
            Outbox outbox,
            AuditTrail audit,
            SalonTime time,
            ProbeProvider provider,
            PlatformTransactionManager transactions) {
        this.outbox = outbox;
        this.audit = audit;
        this.time = time;
        this.provider = provider;
        this.writes = new TransactionTemplate(transactions);
        this.reads = new TransactionTemplate(transactions);
        this.reads.setReadOnly(true);
    }

    /** What a probe asks of its provider. */
    record Request(int failures, long delayMs, boolean abort) {}

    /** One audit entry of a probe. */
    record AuditView(String action, Instant occurredAt, String actorKind, Map<String, Object> after, String traceId) {}

    /** A probe: its message's state, the calls its provider received, and its audit entries. */
    record ProbeView(
            UUID id,
            String status,
            int attempts,
            int providerCalls,
            Instant nextAttemptAt,
            String lastError,
            Instant createdAt,
            String createdAtLocal,
            Instant sentAt,
            List<AuditView> audit) {}

    /** Thrown inside the transaction when the probe asked for it, after both rows were written. */
    static final class ProbeAborted extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        ProbeAborted() {
            super("The probe asked for its transaction to be aborted after writing", null, false, false);
        }
    }

    ProbeView create(Request request) {
        CurrentTenant salon = TenantContext.require();
        UUID id = writes.execute(status -> {
            Instant now = time.now();
            Map<String, Object> asked = new LinkedHashMap<>();
            asked.put("failures", request.failures());
            asked.put("delayMs", request.delayMs());
            UUID messageId = outbox.enqueue(ProbeProvider.CHANNEL, asked, now);
            Map<String, Object> after = new LinkedHashMap<>(asked);
            after.put("abort", request.abort());
            audit.record(
                    new AuditAction(
                            Actor.system("local-probe"), CREATED, SUBJECT, messageId.toString(), null, null, after),
                    now);
            if (request.abort()) {
                throw new ProbeAborted();
            }
            return messageId;
        });
        return find(id).orElseThrow();
    }

    Optional<ProbeView> find(UUID id) {
        CurrentTenant salon = TenantContext.require();
        return reads.execute(status -> outbox.find(id)
                .filter(message -> ProbeProvider.CHANNEL.equals(message.channel()))
                .map(message -> view(
                        salon,
                        message,
                        audit.about(SUBJECT, id.toString()).stream()
                                .map(entry -> new AuditView(
                                        entry.action(),
                                        seconds(entry.occurredAt()),
                                        entry.actorKind(),
                                        entry.after(),
                                        entry.traceId()))
                                .toList())));
    }

    private ProbeView view(CurrentTenant salon, OutboxMessageView message, List<AuditView> entries) {
        return new ProbeView(
                message.id(),
                message.status().value(),
                message.attempts(),
                provider.callsFor(message.id()),
                seconds(message.nextAttemptAt()),
                message.lastError(),
                seconds(message.createdAt()),
                SalonTime.formatLocal(message.createdAt(), salon.timeZone()),
                seconds(message.sentAt()),
                entries);
    }

    /** The probe shows its times to the second, as its local time is shown. */
    private static Instant seconds(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.SECONDS);
    }
}
