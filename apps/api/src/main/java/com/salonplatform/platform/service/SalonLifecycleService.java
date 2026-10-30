package com.salonplatform.platform.service;

import com.salonplatform.platform.domain.Paging;
import com.salonplatform.platform.dto.LifecyclePage;
import com.salonplatform.platform.dto.SalonDetail;
import java.util.UUID;

/**
 * Suspending, reactivating and closing a salon (PLT-06; docs/architecture/operator-console.md,
 * "Suspension, closure, export and retention"). Each action is one platform transaction holding the
 * salon's registry row lock: the state change, the lifecycle event and the platform audit commit
 * together, or nothing does. A suspended salon answers {@code salon_suspended} on its next request;
 * its sessions are refused, not revoked, and billing goes on. Reactivation restores the access state
 * billing implies at that instant. Closure is irreversible: the salon is unknown on its next request,
 * its sessions are revoked and its undelivered messages cancelled (through identity's and the outbox's
 * guarded functions), its export is scheduled and its deletion falls due 30 days later.
 */
public interface SalonLifecycleService {

    record ChangeInput(Long version, String reason) {}

    record ClosureInput(Long version, String confirmSlug, String reason) {}

    /** Suspends an active salon (whatever its billing state). */
    SalonDetail suspend(UUID salonId, ChangeInput input);

    /**
     * Reactivates a suspended salon: it is active again, with the access state its unpaid invoices imply
     * now ({@code read_only} when one is 14 or more days overdue), so its unexpired sessions work again.
     */
    SalonDetail reactivate(UUID salonId, ChangeInput input);

    /**
     * Closes an active or suspended salon for good, once the exact slug is typed to confirm. In the same
     * transaction: its sessions are revoked, its undelivered messages cancelled and its export scheduled.
     */
    SalonDetail close(UUID salonId, ClosureInput input);

    /** The salon's lifecycle history, newest first. */
    LifecyclePage history(UUID salonId, Paging paging);
}
