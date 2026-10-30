package com.salonplatform.identity.service.impl;

import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.NotFoundException;
import java.util.Map;
import java.util.UUID;

/** Where the local fixture hooks may seed and reset identity records. */
final class SyntheticSalons {

    private static final Map<String, UUID> FIXED = Map.of(
            "alpha", UUID.fromString("a1a1a1a1-0000-4000-8000-000000000001"),
            "beta", UUID.fromString("b2b2b2b2-0000-4000-8000-000000000002"),
            "gamma", UUID.fromString("c3c3c3c3-0000-4000-8000-000000000003"));

    private SyntheticSalons() {}

    /**
     * Local fixtures work for every local salon (F03-S02: provisioned salons too, so that read-only rights can be
     * checked on a billed salon), but a salon named like a fixed synthetic salon must be that salon.
     */
    static void requireCurrentIsSynthetic() {
        CurrentTenant salon = TenantContext.require();
        if (FIXED.containsKey(salon.slug()) && !salon.id().equals(FIXED.get(salon.slug()))) {
            throw new NotFoundException();
        }
    }
}
