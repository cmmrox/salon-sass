/**
 * The catalogue's in-process-only contracts (decision 119): the ones that must join the caller's transaction, because
 * they are asked, or answered, under a guard lock inside a writing transaction (decision 094(f)). {@code booking}
 * answers {@link com.salonplatform.catalogue.inprocess.UpcomingServiceBookings} and {@code staff} answers
 * {@link com.salonplatform.catalogue.inprocess.ServiceMoveBookings} inside the catalogue's transactions, and
 * {@code booking} asks {@link com.salonplatform.catalogue.inprocess.CatalogueUnderGuard} inside its own. They are never
 * exposed remotely; if a module is extracted, each becomes an event with a compensating action
 * ({@code docs/engineering/backend.md}, "Provided interfaces"). A named interface of {@code catalogue}, used by
 * {@code booking} and {@code staff} only.
 */
@NamedInterface("inprocess")
package com.salonplatform.catalogue.inprocess;

import org.springframework.modulith.NamedInterface;
