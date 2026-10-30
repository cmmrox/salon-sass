/**
 * The transactional outbox: messages that leave the system are written in the caller's transaction and
 * delivered afterwards ({@code docs/engineering/backend.md}, "Transactions, outbox and external calls"). A
 * named interface of {@code shared}.
 */
@NamedInterface("outbox")
package com.salonplatform.shared.outbox;

import org.springframework.modulith.NamedInterface;
