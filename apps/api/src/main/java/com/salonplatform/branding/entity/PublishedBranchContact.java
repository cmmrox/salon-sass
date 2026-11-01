package com.salonplatform.branding.entity;

import com.salonplatform.branding.domain.BranchContact;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

/** The published copy of one branch's contact details: what customers see, until the next publication. */
@Entity
@Table(name = "branding_branch_contact_published")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PublishedBranchContact {

    @Id
    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Embedded
    private ContactColumns contact;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** The first published copy of a branch's details, for the current salon, which Hibernate stamps. */
    public static PublishedBranchContact first(BranchContact details, Instant at) {
        PublishedBranchContact published = new PublishedBranchContact();
        published.branchId = details.branchId();
        published.contact = ContactColumns.of(details);
        published.createdAt = at;
        published.updatedAt = at;
        return published;
    }

    public BranchContact toContact() {
        return contact == null ? BranchContact.empty(branchId) : contact.toContact(branchId);
    }

    /** Publishes the draft's details over these. */
    public void republish(BranchContact details, Instant at) {
        contact = ContactColumns.of(details);
        updatedAt = at;
    }
}
