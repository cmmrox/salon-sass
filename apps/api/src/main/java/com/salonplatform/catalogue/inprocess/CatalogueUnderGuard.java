package com.salonplatform.catalogue.inprocess;

import com.salonplatform.catalogue.CatalogueListing;
import java.util.UUID;

/**
 * The catalogue as {@code booking} checks it again under {@link UpcomingServiceBookings#GUARD_LOCK}, inside the
 * writing transaction that places a hold or undoes a no-show (decision 094(f)): the categories and the live services,
 * as {@code catalogue.CatalogueListings} answers them, so that a service deleted meanwhile is seen.
 *
 * <p><strong>In-process only</strong> (decision 119): the check must be made under the lock, in the caller's
 * transaction, so an implementation joins it, and is given the branch, which the caller settled before its
 * transaction, rather than asking {@code branding} for it inside that transaction. {@code catalogue.CatalogueListings}
 * reads in a transaction of its own and refuses to be called inside a writing one. If {@code catalogue} is ever
 * extracted, the guard becomes an event with a compensating action, not a remote call.
 */
public interface CatalogueUnderGuard {

    /**
     * The branch's categories in order and its live services by name, in the caller's transaction.
     *
     * @throws IllegalStateException outside a transaction
     */
    CatalogueListing current(UUID branchId);
}
