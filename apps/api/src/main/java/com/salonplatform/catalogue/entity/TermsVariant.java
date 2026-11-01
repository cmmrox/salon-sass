package com.salonplatform.catalogue.entity;

import com.salonplatform.catalogue.ServiceVariant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/**
 * One variant of one terms version (CAT-03, decision 095): written with its version and never changed, like it.
 * {@code variantId} is the variant's stable id, the same in every version of the service while the variant exists,
 * so a booking (F07) names the variant it was made for; this row's own id is only its key.
 */
@Entity
@Immutable
@Table(name = "catalogue_terms_variant")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TermsVariant {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "terms_version_id", nullable = false, updatable = false)
    private UUID termsVersionId;

    @Column(name = "variant_id", nullable = false, updatable = false)
    private UUID variantId;

    @Column(name = "position", nullable = false, updatable = false)
    private int position;

    @Column(name = "name", nullable = false, updatable = false)
    private String name;

    @Column(name = "price_minor", nullable = false, updatable = false)
    private long priceMinor;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "segment_kinds", nullable = false, updatable = false)
    private String[] segmentKinds;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "segment_minutes", nullable = false, updatable = false)
    private Integer[] segmentMinutes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    /**
     * The variant at a position (from 1) of a version's set.
     *
     * @param version the version it belongs to, written in the same transaction
     */
    public static TermsVariant of(TermsVersion version, int position, ServiceVariant variant) {
        TermsVariant row = new TermsVariant();
        row.id = UUID.randomUUID();
        row.branchId = version.getBranchId();
        row.termsVersionId = version.getId();
        row.variantId = variant.variantId();
        row.position = position;
        row.name = variant.name();
        row.priceMinor = variant.priceMinor();
        row.segmentKinds = StoredSegments.kinds(variant.segments());
        row.segmentMinutes = StoredSegments.minutes(variant.segments());
        row.createdAt = version.getCreatedAt();
        row.updatedAt = version.getCreatedAt();
        return row;
    }

    /** The variant this row holds. */
    public ServiceVariant variant() {
        return new ServiceVariant(variantId, name, priceMinor, StoredSegments.of(segmentKinds, segmentMinutes));
    }
}
