/**
 * How the modules reach the database ({@code docs/engineering/backend.md}, "Persistence and migrations"): what
 * every module's Spring Data repositories share, the platform database pool, and the tenant pool's
 * transactions, limits, reconnection and start checks. A named interface of {@code shared}.
 */
@NamedInterface("persistence")
package com.salonplatform.shared.persistence;

import org.springframework.modulith.NamedInterface;
