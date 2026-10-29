package com.salonplatform.platform.service;

import com.salonplatform.platform.repository.SalonEntitlementsStore;
import com.salonplatform.shared.entitlements.SalonEntitlements;
import com.salonplatform.shared.tenancy.TenantContext;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Platform's answer to {@link SalonEntitlements} (docs/architecture/operator-console.md, "Entitlements"):
 * the current salon's plan limits and features, read on the caller's own tenant transaction, which can
 * see only the salon set in that transaction and locks its registry row for share until the transaction
 * ends. Staff seats are enforced by identity with it; branches, message allowance, custom domain and
 * dedicated app by the features that create those things.
 */
@Service
class SalonEntitlementsService implements SalonEntitlements {

    private final SalonEntitlementsStore store;

    SalonEntitlementsService(SalonEntitlementsStore store) {
        this.store = store;
    }

    @Override
    public Optional<Limits> forCurrentSalon() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Entitlements are read inside the writable transaction that enforces them");
        }
        TenantContext.resolved().orElseThrow(() -> new IllegalStateException("Entitlements belong to a salon"));
        return store.forCurrentSalon();
    }
}
