package com.salonplatform.platform.service;

import com.salonplatform.platform.domain.Paging;
import com.salonplatform.platform.dto.PlanDefinition;
import com.salonplatform.platform.dto.PlanPage;
import java.util.UUID;

/**
 * Immutable, unpriced plan definitions (docs/architecture/operator-console.md): a unique key, a name,
 * staff seat and branch limits, a message allowance and two explicit feature flags. Every field is
 * required; there is no implicit unlimited value, no coercion and no arbitrary feature key. A new
 * definition gets a new id and key; nothing edits or removes one. Enforcement is F03-S02.
 */
public interface PlanCatalogueService {

    /** A plan request as received; validated here, after authorization. */
    record PlanInput(
            String key,
            String name,
            Integer staffSeats,
            Integer branches,
            Integer messageAllowance,
            Boolean customDomain,
            Boolean dedicatedApp) {}

    /** The plans, a page at a time. */
    PlanPage list(Paging paging);

    /** One plan. */
    PlanDefinition get(UUID id);

    /** The result of a creation: whether it was a replay, and the definition. */
    record Created(boolean replayed, PlanDefinition plan) {}

    /** Creates a plan; the same key with the same input answers the original plan. */
    Created create(String idempotencyKey, PlanInput input);
}
