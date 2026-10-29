package com.salonplatform.platform;

import com.salonplatform.shared.entitlements.SalonEntitlements;
import com.salonplatform.shared.tenancy.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Platform's public entitlements facade (docs/architecture/operator-console.md, "Entitlements"): the
 * current salon's plan limits and features, read through {@code salon_entitlements()} on the caller's
 * own tenant transaction, which can see only the salon set in that transaction and locks its registry
 * row for share until the transaction ends. Staff seats are enforced by identity with it; branches,
 * message allowance, custom domain and dedicated app by the features that create those things.
 */
@Service
public class EntitlementsFacade implements SalonEntitlements {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Optional<Limits> forCurrentSalon() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Entitlements are read inside the writable transaction that enforces them");
        }
        TenantContext.resolved().orElseThrow(() -> new IllegalStateException("Entitlements belong to a salon"));
        // A native query's result list is untyped; each row of this one is an Object[] of its six columns.
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT plan_id, staff_seats, branches, message_allowance, custom_domain, dedicated_app
                  FROM salon_entitlements()
                """).getResultList();
        if (rows.isEmpty() || rows.get(0)[0] == null) {
            return Optional.empty();
        }
        Object[] row = rows.get(0);
        return Optional.of(new Limits(
                ((Number) row[1]).intValue(),
                ((Number) row[2]).intValue(),
                ((Number) row[3]).intValue(),
                (Boolean) row[4],
                (Boolean) row[5]));
    }
}
