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

/**
 * The draft of one branch's contact details, which only the salon's settings staff see. It exists from
 * the branch's creation; its version is the salon's profile draft's ({@link ProfileDraft}).
 */
@Entity
@Table(name = "branding_branch_contact_draft")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BranchContactDraft {

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

    public BranchContact toContact() {
        return contact == null ? BranchContact.empty(branchId) : contact.toContact(branchId);
    }

    /** Replaces the details, when they differ, with ones the profile's rules accepted. */
    public void replace(BranchContact details, Instant at) {
        if (!details.equals(toContact())) {
            contact = ContactColumns.of(details);
            updatedAt = at;
        }
    }
}
