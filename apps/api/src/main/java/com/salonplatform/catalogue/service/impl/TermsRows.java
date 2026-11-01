package com.salonplatform.catalogue.service.impl;

import com.salonplatform.catalogue.entity.TermsVariant;
import com.salonplatform.catalogue.entity.TermsVersion;
import com.salonplatform.catalogue.repository.TermsVariantRepository;
import com.salonplatform.catalogue.repository.TermsVersionRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Terms versions read and written with their variants (F05-S02): two queries for any number of versions, and a new
 * version written with its variant rows. Joins the caller's transaction.
 */
@Component
class TermsRows {

    /** A version as stored, and its terms with its variants. */
    record Read(TermsVersion row, TermsVersion.Terms terms) {}

    private final TermsVersionRepository versions;
    private final TermsVariantRepository variants;

    TermsRows(TermsVersionRepository versions, TermsVariantRepository variants) {
        this.versions = versions;
        this.variants = variants;
    }

    Optional<Read> find(UUID versionId) {
        return versions.findVersion(versionId)
                .map(row -> new Read(row, row.terms(variants.findOfVersions(List.of(row.getId())))));
    }

    Read require(UUID versionId) {
        return find(versionId).orElseThrow();
    }

    /** The versions by their ids, each with its variants. */
    Map<UUID, Read> all(Collection<UUID> versionIds) {
        if (versionIds.isEmpty()) {
            return Map.of();
        }
        List<TermsVersion> rows = versions.findVersions(versionIds);
        Map<UUID, List<TermsVariant>> byVersion = variants.findOfVersions(versionIds).stream()
                .collect(Collectors.groupingBy(TermsVariant::getTermsVersionId));
        return rows.stream()
                .collect(Collectors.toMap(
                        TermsVersion::getId,
                        row -> new Read(row, row.terms(byVersion.getOrDefault(row.getId(), List.of())))));
    }

    /** Writes a new version and its variants, in order from position 1. */
    Read write(TermsVersion row, TermsVersion.Terms terms) {
        versions.persist(row);
        for (int i = 0; i < terms.variants().size(); i++) {
            variants.persist(TermsVariant.of(row, i + 1, terms.variants().get(i)));
        }
        return new Read(row, terms);
    }
}
