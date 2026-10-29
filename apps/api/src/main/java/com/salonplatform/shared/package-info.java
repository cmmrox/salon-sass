/**
 * The {@code shared} module: what every other module builds on, and no business rules.
 *
 * <p>It depends on no other module. What it owns and the rules between modules are in
 * {@code docs/engineering/backend.md}.
 */
@ApplicationModule(allowedDependencies = {})
package com.salonplatform.shared;

import org.springframework.modulith.ApplicationModule;
