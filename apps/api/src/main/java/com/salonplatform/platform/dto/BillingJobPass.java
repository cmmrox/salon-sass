package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What one pass did: per subscription that changed, and the subscriptions that failed. */
@Schema(hidden = true)
public record BillingJobPass(Instant at, List<SubscriptionPass> changed, List<UUID> failed) {}
