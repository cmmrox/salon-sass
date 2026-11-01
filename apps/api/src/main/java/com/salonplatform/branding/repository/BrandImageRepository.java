package com.salonplatform.branding.repository;

import com.salonplatform.branding.entity.BrandImage;
import com.salonplatform.shared.persistence.NewEntities;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The current salon's brand images (tenant filter and RLS). Only {@link #findById} reads an image's bytes;
 * everything else reads what an image is without them.
 */
public interface BrandImageRepository extends JpaRepository<BrandImage, UUID>, NewEntities<BrandImage> {

    /** What an image is, without its bytes. */
    record Facts(UUID id, String kind, int width, int height) {}

    @Query(
            "select new com.salonplatform.branding.repository.BrandImageRepository$Facts(i.id, i.kind, i.width, i.height)"
                    + " from BrandImage i where i.id in :ids")
    List<Facts> factsOf(@Param("ids") Collection<UUID> ids);

    /** Marks these images used (the draft or the published brand names them). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update BrandImage i set i.unusedSince = null, i.updatedAt = :at"
            + " where i.id in :ids and i.unusedSince is not null")
    int markUsed(@Param("ids") Collection<UUID> ids, @Param("at") Instant at);

    /** Marks every image no longer used, from now, keeping the time of one already unused. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update BrandImage i set i.unusedSince = :at, i.updatedAt = :at"
            + " where i.unusedSince is null and i.id not in :used")
    int markUnusedExcept(@Param("used") Collection<UUID> used, @Param("at") Instant at);

    /**
     * Removes the images unused since before {@code cutoff} that neither the draft nor the published brand
     * names, checked again here in the same statement, so an image in use is never removed.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from BrandImage i
             where i.unusedSince is not null and i.unusedSince <= :cutoff
               and not exists (select 1 from BrandDraft d where d.logoImageId = i.id or d.coverImageId = i.id)
               and not exists (select 1 from SalonBrand b where b.logoImageId = i.id or b.coverImageId = i.id)
            """)
    int removeUnusedSince(@Param("cutoff") Instant cutoff);
}
