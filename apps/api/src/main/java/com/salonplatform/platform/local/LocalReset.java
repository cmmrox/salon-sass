package com.salonplatform.platform.local;

import com.salonplatform.platform.application.OperatorAccounts;
import com.salonplatform.platform.application.SalonExports;
import com.salonplatform.platform.domain.LifecycleTerms;
import com.salonplatform.platform.infrastructure.LocalResetStore;
import com.salonplatform.shared.jobs.LocalJobRewind;
import com.salonplatform.shared.jobs.TenantJobActivity;
import com.salonplatform.shared.lifecycle.TenantDataModule;
import com.salonplatform.shared.persistence.PlatformDatabase;
import com.salonplatform.shared.time.ResettableClock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code local} only: the reset to the synthetic baseline (docs/architecture/operator-console.md,
 * "Local test hooks"). Every salon but Alpha, Beta and Gamma is removed: its tenant data through the
 * registered erasers (the same path the retention job takes), then its platform rows (billing, export,
 * lifecycle history, receipts, platform audit and the registry row or tombstone). Hook-created
 * operators are reset, the test clock returns to real time, and recurring-job occurrences the test
 * clock pushed into the future are brought back to now. Alpha, Beta and Gamma and their data are never
 * touched.
 *
 * <p>The platform runtime role cannot delete billing records, the platform audit or a tombstone, and
 * keeps those grants in every environment. This reset alone uses the owning role's connection (the
 * migration credentials, which exist only on a developer's machine in the {@code local} profile), and
 * lifts the platform audit's and the registry's append-only triggers inside its own transaction only.
 */
@Component
@Profile("local")
class LocalReset {

    private static final Logger log = LoggerFactory.getLogger(LocalReset.class);

    /** The synthetic baseline (docs/qa/test-plan.md): never removed. */
    static final Set<UUID> BASELINE = Set.of(
            UUID.fromString("a1a1a1a1-0000-4000-8000-000000000001"),
            UUID.fromString("b2b2b2b2-0000-4000-8000-000000000002"),
            UUID.fromString("c3c3c3c3-0000-4000-8000-000000000003"));

    record Result(int removedSalons, List<UUID> salonIds, int jobsRescheduled, String clock) {}

    private final PlatformDatabase platform;
    private final SalonExports exports;
    private final OperatorAccounts accounts;
    private final LocalEmailCapture emails;
    private final TenantJobActivity activity;
    private final ObjectProvider<ResettableClock> clock;
    private final LocalResetStore store;
    private final LocalJobRewind jobs;

    LocalReset(
            PlatformDatabase platform,
            SalonExports exports,
            OperatorAccounts accounts,
            LocalEmailCapture emails,
            TenantJobActivity activity,
            ObjectProvider<ResettableClock> clock,
            LocalResetStore store,
            LocalJobRewind jobs) {
        this.platform = platform;
        this.exports = exports;
        this.accounts = accounts;
        this.emails = emails;
        this.activity = activity;
        this.clock = clock;
        this.store = store;
        this.jobs = jobs;
    }

    synchronized Result reset() {
        clock.ifAvailable(ResettableClock::reset);
        Instant now = Instant.now();
        List<UUID> removed = new ArrayList<>();
        for (LocalResetStore.Salon salon : store.salons()) {
            if (BASELINE.contains(salon.id())) {
                continue;
            }
            erase(salon.id(), salon.status(), salon.deleteAt(), now);
            store.removePlatformRows(salon.id());
            removed.add(salon.id());
        }
        int rescheduled = jobs.rewindTo(now);
        accounts.resetFixtures();
        emails.clearSalons(new HashSet<>(removed));
        activity.clear();
        log.info("Local reset: {} salons removed, {} job occurrences brought back to now", removed.size(), rescheduled);
        return new Result(removed.size(), removed, rescheduled, "real");
    }

    /**
     * Erases the salon's tenant data through the registered erasers. Their database functions accept only
     * a closed salon at or after its deletion instant, so an open salon is first closed (as the owner) and
     * every eraser is called at its deletion instant.
     */
    private void erase(UUID id, String status, Instant deleteAt, Instant now) {
        if ("deleted".equals(status)) {
            return;
        }
        Instant at;
        if ("closed".equals(status)) {
            at = deleteAt.isAfter(now) ? deleteAt : now;
        } else {
            Instant closeAt = now;
            at = LifecycleTerms.deleteAt(closeAt);
            store.close(id, closeAt, at);
        }
        for (Map.Entry<TenantDataModule, TenantDataModule.Table> entry : exports.tables()) {
            while (platform.inTransaction(
                            tx -> entry.getKey().erase(entry.getValue().name(), id, at, 1000))
                    > 0) {
                // Bounded batches until the table holds nothing of the salon.
            }
        }
    }
}
