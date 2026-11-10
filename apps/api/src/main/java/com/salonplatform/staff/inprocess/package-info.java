/**
 * Staff's in-process-only contracts (decision 119): the ones that must join the caller's transaction, because they
 * are asked, or answered, under a guard lock inside a writing transaction (decision 094(f)). {@code booking} answers
 * {@link com.salonplatform.staff.inprocess.UpcomingMemberBookings} inside the team's transactions, and asks
 * {@link com.salonplatform.staff.inprocess.TeamUnderGuard} inside its own. They are never exposed remotely; if a module
 * is extracted, each becomes an event with a compensating action ({@code docs/engineering/backend.md}, "Provided
 * interfaces"). A named interface of {@code staff}, used by {@code booking} only.
 */
@NamedInterface("inprocess")
package com.salonplatform.staff.inprocess;

import org.springframework.modulith.NamedInterface;
