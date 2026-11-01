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
 * The salon's brand draft (F04-S02): only its settings staff see it until one of them publishes. One per
 * salon, keyed by the salon itself, from its creation. Its {@code version} works as the profile draft's
 * ({@link ProfileDraft}): a plain column moved on by one by every change, under the row lock every change
 * takes first.
 */
@Entity
@Table(name = "branding_brand_draft")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BrandDraft {

    @Id
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "tagline")
    private String tagline;

    @Column(name = "brand_colour", nullable = false)
    private String brandColour;

    @Column(name = "logo_image_id")
    private UUID logoImageId;

    @Column(name = "cover_image_id")
    private UUID coverImageId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public BrandContent content() {
        return new BrandContent(displayName, tagline, brandColour, logoImageId, coverImageId);
    }

    /** Records a change to the draft, made under its row lock; the version moves on by one. */
    public void changed(BrandContent content, Instant at) {
        version++;
        displayName = content.name();
        tagline = content.tagline();
        brandColour = content.colour();
        logoImageId = content.logo();
        coverImageId = content.cover();
        updatedAt = at;
    }
}
