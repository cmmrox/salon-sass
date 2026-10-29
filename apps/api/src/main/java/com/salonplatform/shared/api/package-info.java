/**
 * Conventions every module's controllers share: problem details with their stable {@code ErrorCode},
 * request paths and trace ids ({@code docs/engineering/backend.md}, "API conventions"), and the service's own
 * metadata endpoint. A named interface of {@code shared}.
 */
@NamedInterface("api")
package com.salonplatform.shared.api;

import org.springframework.modulith.NamedInterface;
