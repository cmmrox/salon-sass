package com.salonplatform.shared.local;

import com.salonplatform.shared.local.dto.ProbeView;
import java.util.Optional;
import java.util.UUID;

/**
 * The probe's use cases: write one outbox message on the channel {@code probe} and one audit entry
 * ({@code probe.created}) in one transaction, for the salon of the request's hostname; and show a
 * probe's message, its audit entries and the provider calls it received.
 */
public interface Probes {

    /** What a probe asks of its provider. */
    record Request(int failures, long delayMs, boolean abort) {}

    /** Writes a probe's message and audit entry, or rolls both back when the probe asks to abort. */
    ProbeView create(Request request);

    /** One of the current salon's probes. */
    Optional<ProbeView> find(UUID id);
}
