package com.salonplatform.staff.repository;

import com.salonplatform.shared.persistence.NewEntities;
import com.salonplatform.staff.entity.MemberPhoto;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The current salon's team member photos (tenant filter and RLS). */
public interface MemberPhotoRepository extends JpaRepository<MemberPhoto, UUID>, NewEntities<MemberPhoto> {

    /** A photo by its id, through a query so that the tenant filter applies as it does to every read. */
    @Query("select p from MemberPhoto p where p.id = :id")
    Optional<MemberPhoto> findPhoto(@Param("id") UUID photoId);

    /** Removes a photo no member holds any longer. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from MemberPhoto p where p.id = :id")
    int remove(@Param("id") UUID photoId);
}
