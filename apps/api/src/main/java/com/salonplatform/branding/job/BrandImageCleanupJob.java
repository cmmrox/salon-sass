package com.salonplatform.branding.job;

import com.salonplatform.branding.repository.BrandImageRepository;
import com.salonplatform.shared.jobs.TenantJob;
import com.salonplatform.shared.tenancy.CurrentTenant;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Removes the brand images that neither the draft nor the published brand has used for 24 hours (F04-S02
 * AC-10), hourly, one salon at a time in the scheduler's tenant transaction, so it can reach no other
 * salon's images. The 24 hours let a person upload, preview and save without losing the image; they count
 * from when an image stopped being used, or from its upload when it never was.
 */
@Component
@RequiredArgsConstructor
class BrandImageCleanupJob implements TenantJob {

    static final Duration GRACE = Duration.ofHours(24);

    private final BrandImageRepository images;

    @Override
    public String name() {
        return "branding-image-cleanup";
    }

    @Override
    public Duration every() {
        return Duration.ofHours(1);
    }

    @Override
    public void runFor(CurrentTenant salon, Instant now) {
        images.removeUnusedSince(now.minus(GRACE));
    }
}
