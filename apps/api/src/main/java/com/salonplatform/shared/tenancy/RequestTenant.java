package com.salonplatform.shared.tenancy;

import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * What one request knows about its salon: the slug its hostname names, and once asked, what the
 * registry says about it. Lives only as long as the scoped value that holds it.
 */
final class RequestTenant {

    /** What the registry said: no such salon, a suspended one, or the salon the request is for. */
    private record Resolution(Optional<CurrentTenant> salon, boolean suspended) {

        static final Resolution NONE = new Resolution(Optional.empty(), false);

        static final Resolution SUSPENDED = new Resolution(Optional.empty(), true);

        static Resolution of(CurrentTenant salon) {
            return new Resolution(Optional.of(salon), false);
        }
    }

    private final String candidateSlug;

    private final TenantDirectory directory;

    private final ReentrantLock lock = new ReentrantLock();

    /** Null until resolved. */
    private volatile Resolution resolution;

    private RequestTenant(String candidateSlug, TenantDirectory directory, Resolution resolution) {
        this.candidateSlug = candidateSlug;
        this.directory = directory;
        this.resolution = resolution;
    }

    static RequestTenant forHostname(String candidateSlug, TenantDirectory directory) {
        // A hostname that names no salon is resolved already: there is nothing to look up.
        return new RequestTenant(candidateSlug, directory, candidateSlug == null ? Resolution.NONE : null);
    }

    static RequestTenant known(CurrentTenant tenant) {
        return new RequestTenant(tenant.slug(), null, Resolution.of(tenant));
    }

    /** The salon, when it has been resolved and is active. A suspended salon is never returned. */
    Optional<CurrentTenant> resolvedSoFar() {
        Resolution current = resolution;
        return current != null ? current.salon() : Optional.empty();
    }

    /**
     * The active salon of the request, looking the slug up once; a lookup that fails is not
     * remembered, so a later call tries again.
     *
     * @throws TenantNotFoundException when the hostname names no salon
     * @throws TenantSuspendedException when it names a suspended one
     */
    CurrentTenant require() {
        Resolution current = resolve();
        if (current.suspended()) {
            throw new TenantSuspendedException();
        }
        return current.salon().orElseThrow(TenantNotFoundException::new);
    }

    private Resolution resolve() {
        Resolution current = resolution;
        if (current != null) {
            return current;
        }
        lock.lock();
        try {
            if (resolution == null) {
                resolution = directory.find(candidateSlug).map(this::resolutionOf).orElse(Resolution.NONE);
            }
            return resolution;
        }
        finally {
            lock.unlock();
        }
    }

    private Resolution resolutionOf(RegisteredTenant registered) {
        return switch (registered.status()) {
            case ACTIVE -> Resolution.of(new CurrentTenant(registered.id(), candidateSlug));
            // Its id is dropped here: a suspended salon is never the salon of a request.
            case SUSPENDED -> Resolution.SUSPENDED;
        };
    }
}
