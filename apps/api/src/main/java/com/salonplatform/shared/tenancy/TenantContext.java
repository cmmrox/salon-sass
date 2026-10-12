package com.salonplatform.shared.tenancy;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * The salon the current request is for, held in a {@link ScopedValue}: bound by the request filter
 * for exactly the extent of one request, and gone when it ends, so that a reused thread can never
 * carry one salon into another salon's request (docs/architecture/multi-tenancy.md).
 *
 * <p>The filter binds what the hostname names. The registry is asked whether that is an active
 * salon the first time the request needs it ({@link #require()}), which a tenant endpoint does
 * before it opens a transaction; the answer is kept for the rest of the request. Endpoints that are
 * not a salon's ({@code meta}, health) never ask, so they never wait on the database.
 *
 * <p>Everything that reaches the database reads {@link #resolved()}: Hibernate's tenant filter and
 * the per-transaction {@code app.tenant_id}. Where no salon is resolved they see none, and see no
 * row.
 */
public final class TenantContext {

    /**
     * Stands for "no salon" where an id is required. The database refuses it as a tenant id, so no
     * row can ever belong to it.
     */
    public static final UUID NO_TENANT = new UUID(0L, 0L);

    private static final ScopedValue<RequestTenant> REQUEST = ScopedValue.newInstance();

    private TenantContext() {
    }

    /**
     * The salon of the current request, resolving it on first use.
     *
     * @return the salon
     * @throws TenantNotFoundException when the request is not for an active salon, or is not a
     *         request at all
     * @throws org.springframework.dao.DataAccessException when the registry cannot be read
     */
    public static CurrentTenant require() {
        if (!REQUEST.isBound()) {
            throw new TenantNotFoundException();
        }
        return REQUEST.get().resolve().orElseThrow(TenantNotFoundException::new);
    }

    /**
     * The salon of the current request if it has already been resolved; never asks the registry.
     *
     * @return the salon, or nothing
     */
    public static Optional<CurrentTenant> resolved() {
        return REQUEST.isBound() ? REQUEST.get().resolvedSoFar() : Optional.empty();
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
        return ScopedValue.where(REQUEST, RequestTenant.forHostname(candidateSlug, directory)).call(work::call);
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
        return ScopedValue.where(REQUEST, RequestTenant.known(tenant)).call(work::call);
    }
}
