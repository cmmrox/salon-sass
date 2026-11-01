package com.salonplatform.branding.entity;

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
 * A salon's logo or cover image (decision 086): already checked by content and re-encoded, so its bytes
 * are a PNG or JPEG the API made itself. Served by its opaque id. {@code unusedSince} is when the draft
 * and the published brand stopped using it, or when it was uploaded unused; the cleanup job removes one
 * unused for 24 hours.
 */
@Entity
@Table(name = "branding_image")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BrandImage {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "kind", nullable = false, updatable = false)
    private String kind;

    @Column(name = "media_type", nullable = false, updatable = false)
    private String mediaType;

    @Column(name = "width", nullable = false, updatable = false)
    private int width;

    @Column(name = "height", nullable = false, updatable = false)
    private int height;

    @Column(name = "bytes", nullable = false, updatable = false)
    private byte[] bytes;

    @Column(name = "unused_since")
    private Instant unusedSince;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** A new image of the current salon, which Hibernate stamps; unused until a draft names it. */
    public static BrandImage uploaded(String kind, String mediaType, int width, int height, byte[] bytes, Instant at) {
        BrandImage image = new BrandImage();
        image.id = UUID.randomUUID();
        image.kind = kind;
        image.mediaType = mediaType;
        image.width = width;
        image.height = height;
        image.bytes = bytes.clone();
        image.unusedSince = at;
        image.createdAt = at;
        image.updatedAt = at;
        return image;
    }

    /** A copy of the bytes, so the entity's own cannot be changed through it. */
    public byte[] getBytes() {
        return bytes.clone();
    }
}
