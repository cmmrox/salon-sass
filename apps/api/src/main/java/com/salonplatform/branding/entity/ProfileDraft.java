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
 * The salon's profile draft: its description and links here, each branch's contact details in
 * {@link BranchContactDraft}. Only the salon's settings staff see it, until one of them publishes. One
 * per salon, keyed by the salon itself, from the salon's creation.
 *
 * <p>Its {@code version} guards the whole draft, branch details included: every save, publication and
 * discard moves it on by one, under the row lock every change takes first
 * ({@code ProfileDraftRepository.lockCurrent}), which also makes a second writer of the same version wait
 * and then find it moved. It is not JPA's {@code @Version}, deliberately: that moves only when this row
 * itself is dirty, so a change to a branch's details alone, or a publication, would leave it unchanged,
 * and forcing an increment on top of a dirty row counts twice.
 */
@Entity
@Table(name = "branding_profile_draft")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProfileDraft {

    @Id
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "description")
    private String description;

    @Column(name = "instagram_url")
    private String instagramUrl;

    @Column(name = "facebook_url")
    private String facebookUrl;

    @Column(name = "website_url")
    private String websiteUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** The draft's content, with these branch details. */
    public ProfileContent content(List<BranchContact> branches) {
        return new ProfileContent(description, instagramUrl, facebookUrl, websiteUrl, branches);
    }

    /**
     * Records a change to the draft, made under its row lock: its salon-wide fields become these, and the
     * version moves on by one even when nothing in this row changed (a branch's details did, or the draft
     * was published), because the version stands for the whole draft.
     */
    public void changed(ProfileContent content, Instant at) {
        version++;
        description = content.description();
        instagramUrl = content.instagram();
        facebookUrl = content.facebook();
        websiteUrl = content.website();
        updatedAt = at;
    }
}
