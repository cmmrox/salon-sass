/**
 * Conventions every module's controllers share: problem details with their stable {@code ErrorCode},
 * request paths and trace ids ({@code docs/engineering/backend.md}, "API conventions"), and the service's own
 * metadata endpoint; and the request pipeline in front of them: the salon a hostname names, the read-only
 * routes, strict JSON and the request's one log line. A named interface of {@code shared}.
 */
@NamedInterface("web")
package com.salonplatform.shared.web;

import org.springframework.modulith.NamedInterface;
