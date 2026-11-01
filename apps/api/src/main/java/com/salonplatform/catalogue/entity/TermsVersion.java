package com.salonplatform.catalogue.entity;

import com.salonplatform.catalogue.SegmentKind;
import com.salonplatform.catalogue.ServiceSegment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/**
 * One version of a service's terms (CAT-04): written once and never changed, so a booking that keeps its id keeps
 * its price and time. Hibernate never updates it ({@link Immutable}), the application role has no update or delete
 * grant, and the database refuses an update by any role. Its time is ordered segments (decision 074), stored as two
 * arrays read position by position.
 */
@Entity
@Immutable
@Table(name = "catalogue_terms_version")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TermsVersion {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "service_id", nullable = false, updatable = false)
    private UUID serviceId;

    @Column(name = "number", nullable = false, updatable = false)
    private long number;

    @Column(name = "price_minor", nullable = false, updatable = false)
    private long priceMinor;

    @Column(name = "currency", nullable = false, updatable = false)
    private String currency;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "segment_kinds", nullable = false, updatable = false)
    private String[] segmentKinds;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "segment_minutes", nullable = false, updatable = false)
    private Integer[] segmentMinutes;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    /** The terms themselves, as a version holds them. */
    public record Terms(long priceMinor, String currency, List<ServiceSegment> segments) {

        public Terms {
            segments = List.copyOf(segments);
        }
    }

    /**
     * A new version of a service's terms, which Hibernate stamps with the current salon.
     *
     * @param number the service's previous version's number plus one (1 for its first)
     */
    public static TermsVersion made(
            UUID branchId, UUID serviceId, long number, Terms terms, UUID createdBy, Instant at) {
        TermsVersion row = new TermsVersion();
        row.id = UUID.randomUUID();
        row.branchId = branchId;
        row.serviceId = serviceId;
        row.number = number;
        row.priceMinor = terms.priceMinor();
        row.currency = terms.currency();
        row.segmentKinds = terms.segments().stream()
                .map(segment -> segment.kind().name().toLowerCase(Locale.ROOT))
                .toArray(String[]::new);
        row.segmentMinutes =
                terms.segments().stream().map(ServiceSegment::minutes).toArray(Integer[]::new);
        row.createdBy = createdBy;
        row.createdAt = at;
        row.updatedAt = at;
        return row;
    }

    /** The terms this version holds. */
    public Terms terms() {
        return new Terms(priceMinor, currency, segments());
    }

    /** Its segments, in order. */
    public List<ServiceSegment> segments() {
        List<ServiceSegment> segments = new ArrayList<>();
        for (int i = 0; i < segmentKinds.length; i++) {
            segments.add(new ServiceSegment(
                    SegmentKind.valueOf(segmentKinds[i].toUpperCase(Locale.ROOT)), segmentMinutes[i]));
        }
        return List.copyOf(segments);
    }
}
