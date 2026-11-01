package com.salonplatform.catalogue.entity;

import com.salonplatform.catalogue.ServiceDeposit;
import com.salonplatform.catalogue.ServiceSegment;
import com.salonplatform.catalogue.ServiceVariant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
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
 *
 * <p>From F05-S02 a version also holds the service's deposit and, for a service with variants, the set's name; its
 * variants are {@link TermsVariant} rows written with it. A version with variants holds the lowest of their prices
 * and the shortest of their times itself (decision 095), so that F05-S01's readers of {@code price_minor} and the
 * segments keep working.
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

    @Column(name = "variant_set_name", updatable = false)
    private String variantSetName;

    @Column(name = "deposit_kind", nullable = false, updatable = false)
    private String depositKind;

    @Column(name = "deposit_percent", updatable = false)
    private Integer depositPercent;

    @Column(name = "deposit_amount_minor", updatable = false)
    private Long depositAmountMinor;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    /**
     * The terms themselves, as a version holds them with its variants.
     *
     * @param priceMinor with variants, the lowest of their prices
     * @param segments with variants, the shortest variant's
     * @param variantSetName null without variants
     * @param variants in order; empty without variants
     */
    public record Terms(
            long priceMinor,
            String currency,
            List<ServiceSegment> segments,
            String variantSetName,
            List<ServiceVariant> variants,
            ServiceDeposit deposit) {

        public Terms {
            segments = List.copyOf(segments);
            variants = List.copyOf(variants);
        }
    }

    /**
     * A new version of a service's terms, which Hibernate stamps with the current salon. Its variants are written
     * by the caller as {@link TermsVariant#of} rows in the same transaction.
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
        row.segmentKinds = StoredSegments.kinds(terms.segments());
        row.segmentMinutes = StoredSegments.minutes(terms.segments());
        row.variantSetName = terms.variantSetName();
        switch (terms.deposit()) {
            case ServiceDeposit.SalonRule _ -> row.depositKind = "salon_rule";
            case ServiceDeposit.None _ -> row.depositKind = "none";
            case ServiceDeposit.Percentage percentage -> {
                row.depositKind = "percentage";
                row.depositPercent = percentage.percent();
            }
            case ServiceDeposit.Amount amount -> {
                row.depositKind = "amount";
                row.depositAmountMinor = amount.amountMinor();
            }
        }
        row.createdBy = createdBy;
        row.createdAt = at;
        row.updatedAt = at;
        return row;
    }

    /** The terms this version holds, with its variants as read (in order, possibly none). */
    public Terms terms(List<TermsVariant> variants) {
        return new Terms(
                priceMinor,
                currency,
                segments(),
                variantSetName,
                variants.stream().map(TermsVariant::variant).toList(),
                deposit());
    }

    /** Its segments, in order. */
    public List<ServiceSegment> segments() {
        return StoredSegments.of(segmentKinds, segmentMinutes);
    }

    /** Its deposit. */
    public ServiceDeposit deposit() {
        return switch (depositKind) {
            case "none" -> new ServiceDeposit.None();
            case "percentage" -> new ServiceDeposit.Percentage(depositPercent);
            case "amount" -> new ServiceDeposit.Amount(depositAmountMinor);
            default -> new ServiceDeposit.SalonRule();
        };
    }
}
