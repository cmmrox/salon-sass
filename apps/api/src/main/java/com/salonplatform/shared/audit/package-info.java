/**
 * The audit trail: the one way to write an audit entry, which joins the caller's transaction
 * ({@code docs/engineering/backend.md}, "Transactions, outbox and external calls"). A named interface of
 * {@code shared}.
 */
@NamedInterface("audit")
package com.salonplatform.shared.audit;

import org.springframework.modulith.NamedInterface;
