/**
 * Attempts counted across every salon: how many times one address has tried something, whichever salon it tried,
 * so that a limit per address does not multiply by the number of salons (decision 120). The counters are a
 * platform-level table with no {@code tenant_id}, reached only through {@code SECURITY DEFINER} functions that
 * return a count. A named interface of {@code shared}.
 */
@NamedInterface("attempts")
package com.salonplatform.shared.attempts;

import org.springframework.modulith.NamedInterface;
