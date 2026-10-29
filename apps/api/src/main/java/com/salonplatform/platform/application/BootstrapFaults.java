package com.salonplatform.platform.application;

/**
 * A seam for the controlled bootstrap failure the local hooks provide (rollback checks): called at
 * each step of salon provisioning, inside its transaction. Outside {@code local} it never fails.
 */
public interface BootstrapFaults {

    /** The provisioning steps a failure can be set at. */
    enum Step {
        OWNER,
        BRAND,
        RECEIPT
    }

    /** Fails the provisioning at this step when a controlled failure is set for it. */
    void reached(Step step);
}
