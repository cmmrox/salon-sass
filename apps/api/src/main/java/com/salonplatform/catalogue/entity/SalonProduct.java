package com.salonplatform.catalogue.entity;

import com.salonplatform.catalogue.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

/**
 * One product the salon sells at the front desk (INV-01, F05-S03), current only: a sale copies what it needs, so a
 * change or a delete rewrites no past sale. Its name is unique in the branch regardless of case. Stock is not
 * tracked (INV-02).
 */
@Entity
@Table(name = "catalogue_product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SalonProduct {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "price_minor", nullable = false)
    private long priceMinor;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "charges_vat", nullable = false)
    private boolean chargesVat;

    @Column(name = "sold_at_checkout", nullable = false)
    private boolean soldAtCheckout;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** What a product is: its name, its price in minor units of its currency, its VAT choice and whether it is sold. */
    public record Details(String name, long priceMinor, String currency, boolean chargesVat, boolean soldAtCheckout) {}

    /** A new product of the branch, which Hibernate stamps with the current salon. */
    public static SalonProduct added(UUID branchId, Details details, Instant at) {
        SalonProduct product = new SalonProduct();
        product.id = UUID.randomUUID();
        product.branchId = branchId;
        product.apply(details);
        product.createdAt = at;
        product.updatedAt = at;
        return product;
    }

    /** Replaces everything the salon keeps of the product. */
    public void change(Details details, Instant at) {
        apply(details);
        updatedAt = at;
    }

    public Details details() {
        return new Details(name, priceMinor, currency, chargesVat, soldAtCheckout);
    }

    /** The product as other modules read it. */
    public Product product() {
        return new Product(id, name, priceMinor, currency, chargesVat, soldAtCheckout);
    }

    private void apply(Details details) {
        name = details.name();
        priceMinor = details.priceMinor();
        currency = details.currency();
        chargesVat = details.chargesVat();
        soldAtCheckout = details.soldAtCheckout();
    }
}
