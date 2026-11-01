package com.salonplatform.branding.service.impl;

import com.salonplatform.branding.domain.BrandContent;
import com.salonplatform.branding.repository.BrandImageRepository;
import com.salonplatform.branding.service.BrandImageView;
import com.salonplatform.branding.service.BrandView;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The brand's images as the draft and the published brand use them, inside the caller's tenant
 * transaction: what each named image is, and which images are in use.
 */
@Component
@RequiredArgsConstructor
class BrandRecords {

    /** Stands in for "no image" where a query needs at least one id: no image has it. */
    private static final UUID NONE = new UUID(0, 0);

    private final BrandImageRepository images;

    /** A brand's view, with what each of its images is. */
    BrandView view(BrandContent content) {
        Map<UUID, BrandImageView> named = facts(Stream.of(content.logo(), content.cover())
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet()));
        return new BrandView(
                content.name(),
                Optional.ofNullable(content.tagline()),
                content.colour(),
                Optional.ofNullable(content.logo()).map(named::get),
                Optional.ofNullable(content.cover()).map(named::get));
    }

    /** Each of the salon's images among these ids, with its kind. */
    Map<UUID, String> kinds(Set<UUID> ids) {
        Map<UUID, String> kinds = new HashMap<>();
        facts(ids).forEach((id, view) -> kinds.put(id, view.kind()));
        return kinds;
    }

    private Map<UUID, BrandImageView> facts(Set<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return images.factsOf(ids).stream()
                .collect(Collectors.toMap(
                        BrandImageRepository.Facts::id,
                        f -> new BrandImageView(f.id(), f.kind(), f.width(), f.height())));
    }

    /**
     * After a change, marks the images the draft and the published brand use as used, and every other one
     * unused from now (one already unused keeps its time), so the cleanup job's 24 hours count from when an
     * image stopped being used.
     */
    void markUse(BrandContent draft, BrandContent published, Instant at) {
        Set<UUID> used = new HashSet<>();
        Stream.of(draft.logo(), draft.cover(), published.logo(), published.cover())
                .filter(java.util.Objects::nonNull)
                .forEach(used::add);
        if (!used.isEmpty()) {
            images.markUsed(used, at);
        }
        used.add(NONE);
        images.markUnusedExcept(used, at);
    }
}
