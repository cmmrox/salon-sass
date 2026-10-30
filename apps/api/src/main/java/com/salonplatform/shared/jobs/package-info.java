/**
 * Recurring work, run once per occurrence across instances and once per active salon
 * ({@code docs/engineering/backend.md}, "Transactions, outbox and external calls"). A named interface of
 * {@code shared}.
 */
@NamedInterface("jobs")
package com.salonplatform.shared.jobs;

import org.springframework.modulith.NamedInterface;
