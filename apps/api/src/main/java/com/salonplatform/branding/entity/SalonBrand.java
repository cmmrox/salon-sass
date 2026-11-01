package com.salonplatform.branding.entity;

import com.salonplatform.branding.domain.BrandContent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

/**
 * A salon's published brand: the name, tagline, colour, logo and cover its customers see, until the next
 * publication (F04-S02). One per salon, keyed by the salon itself; the salon is the request's, stamped and
 * filtered by Hibernate ({@code @TenantId}) and checked again by row-level security.
 */
@Entity
@Table(name = "salon_brand")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SalonBrand {

    @Id
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "brand_colour", nullable = false)
    private String brandColour;

    @Column(name = "tagline")
    private String tagline;

    @Column(name = "logo_image_id")
    private UUID logoImageId;

    @Column(name = "cover_image_id")
    private UUID coverImageId;

    /** When it was last published from the draft; null for a brand provisioning wrote and nobody published. */
    @Column(name = "published_at")
    private Instant publishedAt;

    /** The staff account that last published it; null as {@code publishedAt} is. */
    @Column(name = "published_by")
    private UUID publishedBy;

    /** Written by the database's default when a brand is created, and by every publication after. */
    @Column(name = "updated_at", nullable = false, insertable = false)
    private Instant updatedAt;

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

    public BrandContent content() {
        return new BrandContent(displayName, tagline, brandColour, logoImageId, coverImageId);
    }

    /** Publishes the draft's brand over this one, by this staff member, now. */
    public void republish(BrandContent content, UUID by, Instant at) {
        displayName = content.name();
        tagline = content.tagline();
        brandColour = content.colour();
        logoImageId = content.logo();
        coverImageId = content.cover();
        publishedBy = by;
        publishedAt = at;
        updatedAt = at;
    }
}
