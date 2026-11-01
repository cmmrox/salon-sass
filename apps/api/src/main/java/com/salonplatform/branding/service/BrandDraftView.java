package com.salonplatform.branding.service;

import java.time.Instant;
import java.util.UUID;

/**
 * The salon's brand draft, as its settings staff see it.
 *
 * @param version the draft's version, which the next change must name
 * @param brand the draft's brand
 * @param hasUnpublishedChanges whether the draft differs from the published brand
 * @param publishedAt when the brand was last published from a draft, or null when it never was
 * @param publishedBy the staff account that last published it, or null when it never was
 */
public record BrandDraftView(
        long version, BrandView brand, boolean hasUnpublishedChanges, Instant publishedAt, UUID publishedBy) {}
