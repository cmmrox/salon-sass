package com.salonplatform.catalogue.entity;

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
 * A service's photo (CAT-01, decision 093): already checked by content and re-encoded, so its bytes are a JPEG the
 * API made itself. Served by its opaque id. Removed when its service's photo is replaced or removed, or the
 * service is deleted.
 */
@Entity
@Table(name = "catalogue_photo")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServicePhoto {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "media_type", nullable = false, updatable = false)
    private String mediaType;

    @Column(name = "width", nullable = false, updatable = false)
    private int width;

    @Column(name = "height", nullable = false, updatable = false)
    private int height;

    @Column(name = "bytes", nullable = false, updatable = false)
    private byte[] bytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    /** A new photo of the current salon's branch, which Hibernate stamps. */
    public static ServicePhoto uploaded(
            UUID branchId, String mediaType, int width, int height, byte[] bytes, Instant at) {
        ServicePhoto photo = new ServicePhoto();
        photo.id = UUID.randomUUID();
        photo.branchId = branchId;
        photo.mediaType = mediaType;
        photo.width = width;
        photo.height = height;
        photo.bytes = bytes.clone();
        photo.createdAt = at;
        photo.updatedAt = at;
        return photo;
    }

    /** A copy of the bytes, so the entity's own cannot be changed through it. */
    public byte[] getBytes() {
        return bytes.clone();
    }
}
