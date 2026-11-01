package com.salonplatform.branding.entity;

import com.salonplatform.branding.domain.BranchContact;
import com.salonplatform.branding.domain.ProfileContent;
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
import org.hibernate.annotations.TenantId;

/**
 * The salon's published profile: its description and links here, each branch's contact details in
 * {@link PublishedBranchContact}. What everything that reads the salon is served, until the next
 * publication. At most one per salon, written at the first publication.
 */
@Entity
@Table(name = "branding_profile_published")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PublishedProfile {

    @Id
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "description")
    private String description;

    @Column(name = "instagram_url")
    private String instagramUrl;

    @Column(name = "facebook_url")
    private String facebookUrl;

    @Column(name = "website_url")
    private String websiteUrl;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "published_by", nullable = false)
    private UUID publishedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * The current salon's first publication. Its key is the salon itself, which Hibernate stamps from the
     * request's tenant as it inserts the row ({@code @TenantId} on the id), so it is never chosen here.
     */
    public static PublishedProfile first(ProfileContent content, UUID publishedBy, Instant at) {
        PublishedProfile published = new PublishedProfile();
        published.createdAt = at;
        published.republish(content, publishedBy, at);
        return published;
    }

    /** The published content, with these branch details. */
    public ProfileContent content(List<BranchContact> branches) {
        return new ProfileContent(description, instagramUrl, facebookUrl, websiteUrl, branches);
    }

    /** Publishes the draft's salon-wide fields over these, by this staff member, now. */
    public void republish(ProfileContent content, UUID by, Instant at) {
        description = content.description();
        instagramUrl = content.instagram();
        facebookUrl = content.facebook();
        websiteUrl = content.website();
        publishedBy = by;
        publishedAt = at;
        updatedAt = at;
    }
}
