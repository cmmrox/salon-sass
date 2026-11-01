/**
 * Platform's in-process-only contracts that other modules implement: a step of a new salon's bootstrap, run in
 * the platform transaction that creates the salon, because the salon and what each module gives it from the
 * start commit or roll back as one unit of work ({@code docs/architecture/operator-console.md}, "Platform
 * database boundary and atomic bootstrap"). Declared here, so that a module that already depends on
 * {@code platform} gives a new salon its data without {@code platform} depending on it (decision 087's
 * pattern). Never exposed remotely; if a module is extracted, its step becomes an event it consumes after the
 * salon is created, with a compensating action ({@code docs/engineering/backend.md}, "Provided interfaces").
 */
@NamedInterface("inprocess")
package com.salonplatform.platform.inprocess;

import org.springframework.modulith.NamedInterface;
