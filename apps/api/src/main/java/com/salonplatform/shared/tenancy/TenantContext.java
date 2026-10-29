package com.salonplatform.shared.tenancy;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.slf4j.MDC;

/**
 * The salon the current request is for, held in a {@link ScopedValue}: bound by the request filter
 * for exactly the extent of one request, and gone when it ends, so that a reused thread can never
 * carry one salon into another salon's request (docs/architecture/multi-tenancy.md).
 *
 * <p>The filter binds what the hostname names. The registry is asked what that is the first time
 * the request needs its salon ({@link #require()}), which a tenant endpoint does before it opens a
 * transaction; the answer is kept for the rest of the request. Endpoints that are not a salon's
 * ({@code meta}, health) never ask, so they never wait on the database and answer on any hostname.
 *
 * <p>{@link #require()} is where every tenant endpoint learns that there is no such salon
 * ({@link TenantNotFoundException}, 404 {@code not_found}) or that the salon is suspended
 * ({@link TenantSuspendedException}, 403 {@code salon_suspended}), so each endpoint gives the same
 * two answers without saying so itself.
 *
 * <p>Everything that reaches the database reads {@link #resolved()}: Hibernate's tenant filter and
 * the per-transaction {@code app.tenant_id}. Where no salon is resolved, or the salon is suspended,
 * they see none, and see no row.
 */
public final class TenantContext {

    /**
     * Stands for "no salon" where an id is required. The database refuses it as a tenant id, so no
     * row can ever belong to it.
     */
    public static final UUID NO_TENANT = new UUID(0L, 0L);

    /**
     * The field that names the salon in every log line and span written for it (T-8, NFR-16): set
     * when a request's salon is resolved, and for the extent of {@link #callAs}.
     */
    public static final String LOG_FIELD = "tenant.id";

    private static final ScopedValue<RequestTenant> REQUEST = ScopedValue.newInstance();

    private TenantContext() {}

    /**
     * The salon of the current request, resolving it on first use.
     *
     * @return the salon, which is active
     * @throws TenantNotFoundException when the request's hostname names no salon, or this is not a
     *         request at all
     * @throws TenantSuspendedException when the request's hostname names a suspended salon
     * @throws org.springframework.dao.DataAccessException when the registry cannot be read
     */
    public static CurrentTenant require() {
        if (!REQUEST.isBound()) {
            throw new TenantNotFoundException();
        }
        return REQUEST.get().require();
    }

    /**
     * The salon of the current request if it has already been resolved and is active; never asks
     * the registry.
     *
     * @return the salon, or nothing
     */
    public static Optional<CurrentTenant> resolved() {
        return REQUEST.isBound() ? REQUEST.get().resolvedSoFar() : Optional.empty();
    }

    /**
     * Resolves the current request's salon now, if its hostname names one, without failing: so that
     * every log line of the request carries {@value #LOG_FIELD} (T-8). Used by the request filter
     * for every endpoint except the platform's own, which must not wait on the database. A failure
     * to read the registry is given to {@link #require()} later in the request.
     */
    public static void resolveEarly() {
        if (REQUEST.isBound()) {
            REQUEST.get().resolveEarly();
        }
    }

    /**
     * Runs work for a request whose hostname named this candidate slug. Used by the request filter.
     *
     * @param candidateSlug the slug the hostname names, or null when it names none
     * @param directory where the slug is looked up when the work first needs its salon
     * @param work the request's work
     */
    public static <R> R callForRequest(String candidateSlug, TenantDirectory directory, Callable<R> work)
            throws Exception {
        return ScopedValue.where(REQUEST, RequestTenant.forHostname(candidateSlug, directory))
                .call(work::call);
    }

    /**
     * Runs work for a salon the server has already established, outside a request: for background
     * work that iterates salons explicitly, and for tests. Never call it with a salon taken from
     * anything a client sent.
     *
     * @param tenant the salon
     * @param work the work
     */
    public static <R> R callAs(CurrentTenant tenant, Callable<R> work) throws Exception {
        String outer = MDC.get(LOG_FIELD);
        MDC.put(LOG_FIELD, tenant.id().toString());
        try {
            return ScopedValue.where(REQUEST, RequestTenant.known(tenant)).call(work::call);
        } finally {
            if (outer == null) {
                MDC.remove(LOG_FIELD);
            } else {
                MDC.put(LOG_FIELD, outer);
            }
        }
    }
}
