/**
 * Identity's in-process-only contracts: the ones that must join the caller's platform transaction,
 * because a salon's creation and closure commit or roll back as one unit of work with identity's part
 * of it ({@code docs/architecture/operator-console.md}, "Platform database boundary and atomic
 * bootstrap"). They are never exposed remotely; if identity is extracted, each becomes an event with a
 * compensating action ({@code docs/engineering/backend.md}, "Provided interfaces"). A named interface
 * of {@code identity}, used by {@code platform} only.
 */
@NamedInterface("inprocess")
package com.salonplatform.identity.inprocess;

import org.springframework.modulith.NamedInterface;
