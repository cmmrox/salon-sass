/**
 * Branding's in-process-only contract: a new salon's first brand, inserted in the caller's platform
 * transaction, because a salon and the brand its hostname needs to resolve commit or roll back as one
 * unit of work ({@code docs/architecture/operator-console.md}, "Platform database boundary and atomic
 * bootstrap"). Never exposed remotely; if branding is extracted, it becomes an event with a
 * compensating action ({@code docs/engineering/backend.md}, "Provided interfaces"). A named interface
 * of {@code branding}, used by {@code platform} only.
 */
@NamedInterface("inprocess")
package com.salonplatform.branding.inprocess;

import org.springframework.modulith.NamedInterface;
