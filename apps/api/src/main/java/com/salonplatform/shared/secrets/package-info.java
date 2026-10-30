/**
 * Secret keys kept independent of each other across modules: each module declares the keys it holds, and
 * {@code shared} compares them all, so that no module reads another module's settings to do so
 * ({@code docs/engineering/backend.md}, "Ownership of tables and paths"). A named interface of {@code shared}.
 */
@NamedInterface("secrets")
package com.salonplatform.shared.secrets;

import org.springframework.modulith.NamedInterface;
