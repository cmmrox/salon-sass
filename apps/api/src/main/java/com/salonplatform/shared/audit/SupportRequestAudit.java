package com.salonplatform.shared.audit;

import java.util.LinkedHashMap;
import java.util.Map;

import com.salonplatform.shared.tenancy.SupportGrant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records every API request made under a support session in the salon's own audit trail (F03-S04,
 * PLT-07): the method, the route template (never a raw path with its ids), the outcome and the time.
 * A support request changes nothing (every mutation is refused), so its entry is a separate, bounded
 * write of its own that never fails the read it records: a failure is logged without the request.
 */
@Component
public class SupportRequestAudit {

    public static final String ACTION = "support.request";

    private static final Logger log = LoggerFactory.getLogger(SupportRequestAudit.class);

    /** Seconds the entry's own transaction may take. */
    private static final int TIMEOUT_SECONDS = 2;

    private final AuditTrail audit;
    private final TransactionTemplate transactions;

    SupportRequestAudit(AuditTrail audit, PlatformTransactionManager manager) {
        this.audit = audit;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setTimeout(TIMEOUT_SECONDS);
    }

    /**
     * Writes the entry for one request in the current salon. Never throws.
     *
     * @param grant the support session the request was made under
     * @param method the HTTP method
     * @param route the route template the request matched, or {@code unmatched}
     * @param status the response status
     */
    public void record(SupportGrant grant, String method, String route, int status) {
        try {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("method", method);
            after.put("route", route);
            after.put("status", status);
            transactions.executeWithoutResult(tx -> audit.record(new AuditAction(
                    new Actor(ActorKind.SUPPORT, grant.sessionId().toString()), ACTION, "support_session",
                    grant.sessionId().toString(), null, null, after)));
        }
        catch (RuntimeException ex) {
            log.warn("A support request's audit entry could not be written: {}", ex.getClass().getSimpleName());
        }
    }
}
