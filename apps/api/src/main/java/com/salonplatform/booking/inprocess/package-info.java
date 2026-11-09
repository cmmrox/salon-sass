/**
 * Booking's in-process-only contracts: what payment (F08) and checkout (F10) change on a booking inside their own
 * transactions, because the booking's state and the money that moves it must commit together or not at all
 * (docs/engineering/backend.md, "Booking integrity"). Each joins the caller's writable tenant transaction and refuses
 * to run outside one. Never exposed remotely; if a module is extracted, the call becomes an event with a compensating
 * action ({@code docs/engineering/backend.md}, "Provided interfaces").
 */
@NamedInterface("inprocess")
package com.salonplatform.booking.inprocess;

import org.springframework.modulith.NamedInterface;
