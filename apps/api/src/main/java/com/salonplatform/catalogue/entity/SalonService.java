package com.salonplatform.catalogue.entity;

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
 * One of the salon's services (CAT-01). Its price and time are on its terms versions, never here (CAT-04); it
 * points at the current one. Deleting it makes a tombstone: it leaves every list, frees its name and holds no
 * category or photo, while its terms versions stay for the bookings that keep them.
 */
@Entity
@Table(name = "catalogue_service")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SalonService {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "bookable_online", nullable = false)
    private boolean bookableOnline;

    @Column(name = "photo_id")
    private UUID photoId;

    @Column(name = "current_terms_id", nullable = false)
    private UUID currentTermsId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** What a service is, apart from its terms and photo. */
    public record Details(String name, UUID categoryId, String description, boolean bookableOnline) {}

    /**
     * A new service of the branch, with the id of its first terms version, which the caller writes in the same
     * transaction.
     */
    public static SalonService created(
            UUID id, UUID branchId, Details details, UUID firstTermsId, UUID photoId, Instant at) {
        SalonService service = new SalonService();
        service.id = id;
        service.branchId = branchId;
        service.categoryId = details.categoryId();
        service.name = details.name();
        service.description = details.description();
        service.bookableOnline = details.bookableOnline();
        service.photoId = photoId;
        service.currentTermsId = firstTermsId;
        service.createdAt = at;
        service.updatedAt = at;
        return service;
    }

    /** What the service is now. */
    public Details details() {
        return new Details(name, categoryId, description, bookableOnline);
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void describe(Details details, Instant at) {
        name = details.name();
        categoryId = details.categoryId();
        description = details.description();
        bookableOnline = details.bookableOnline();
        updatedAt = at;
    }

    public void showOnline(boolean online, Instant at) {
        bookableOnline = online;
        updatedAt = at;
    }

    /** Makes a newer terms version current; the earlier ones are kept unchanged. */
    public void retermed(UUID termsId, Instant at) {
        currentTermsId = termsId;
        updatedAt = at;
    }

    /** Sets or removes (null) the photo. */
    public void changePhoto(UUID newPhotoId, Instant at) {
        photoId = newPhotoId;
        updatedAt = at;
    }

    /** Makes the service a tombstone: out of every list, its name free, no category, no photo, not online. */
    public void delete(Instant at) {
        deletedAt = at;
        categoryId = null;
        photoId = null;
        bookableOnline = false;
        updatedAt = at;
    }
}
