package com.salonplatform.branding.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

/**
 * A salon's published brand: the name and colour its customers see. One per salon, keyed by the
 * salon itself; the salon is the request's, stamped and filtered by Hibernate ({@code @TenantId}) and
 * checked again by row-level security.
 */
@Entity
@Table(name = "salon_brand")
public class SalonBrand {

    @Id
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "brand_colour", nullable = false)
    private String brandColour;

    protected SalonBrand() {
    }

    /**
     * A brand for the current salon. The salon is not chosen here: Hibernate stamps the request's.
     *
     * @param displayName the name customers see
     * @param brandColour {@code #RRGGBB}
     */
    public SalonBrand(String displayName, String brandColour) {
        this.displayName = displayName;
        this.brandColour = brandColour;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String displayName() {
        return displayName;
    }

    public String brandColour() {
        return brandColour;
    }
}
