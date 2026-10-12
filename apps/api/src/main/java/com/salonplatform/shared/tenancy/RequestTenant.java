package com.salonplatform.shared.tenancy;

import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * What one request knows about its salon: the slug its hostname names, and once asked, whether the
 * registry knows it. Lives only as long as the scoped value that holds it.
 */
final class RequestTenant {

    private final String candidateSlug;

    private final TenantDirectory directory;

    private final ReentrantLock lock = new ReentrantLock();

    /** Null until resolved. Then the salon, or empty for "no salon". */
    private volatile Optional<CurrentTenant> resolution;

    private RequestTenant(String candidateSlug, TenantDirectory directory, Optional<CurrentTenant> resolution) {
        this.candidateSlug = candidateSlug;
        this.directory = directory;
        this.resolution = resolution;
    }

    static RequestTenant forHostname(String candidateSlug, TenantDirectory directory) {
        // A hostname that names no salon is resolved already: there is nothing to look up.
        return new RequestTenant(candidateSlug, directory, candidateSlug == null ? Optional.empty() : null);
    }

    static RequestTenant known(CurrentTenant tenant) {
        return new RequestTenant(tenant.slug(), null, Optional.of(tenant));
    }

    Optional<CurrentTenant> resolvedSoFar() {
        Optional<CurrentTenant> current = resolution;
        return current != null ? current : Optional.empty();
    }

    /** Looks the slug up once; a lookup that fails is not remembered, so a later call tries again. */
    Optional<CurrentTenant> resolve() {
        Optional<CurrentTenant> current = resolution;
        if (current != null) {
            return current;
        }
        lock.lock();
        try {
            if (resolution == null) {
                resolution = directory.findActive(candidateSlug).map(id -> new CurrentTenant(id, candidateSlug));
            }
            return resolution;
        }
        finally {
            lock.unlock();
        }
    }
}
