package com.salonplatform.catalogue;

import java.util.UUID;

/**
 * The question the catalogue asks about bookings before it deletes a service (CAT-01, decision 093): a service can
 * be deleted only when no upcoming booking uses it; otherwise it can only be hidden.
 *
 * <p>Declared here and implemented by {@code booking} in F07, so that dependencies point one way ({@code booking}
 * depends on {@code catalogue}; decision 087's pattern). Until then the catalogue's own implementation answers
 * that no service has upcoming bookings, so a delete is never blocked; F07 removes that implementation when
 * {@code booking} provides its own. An implementation reads the request's salon, opens its own transaction and
 * does not join the caller's.
 */
public interface UpcomingServiceBookings {

    /**
     * Whether any booking of the request's salon that has not yet taken place, and is not cancelled, uses the
     * service.
     *
     * @throws com.salonplatform.shared.tenancy.TenantNotFoundException when the request names no salon
     */
    boolean anyFor(UUID serviceId);
}
