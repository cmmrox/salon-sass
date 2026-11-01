package com.salonplatform.catalogue.repository;

import com.salonplatform.catalogue.entity.ServicePhoto;
import com.salonplatform.shared.persistence.NewEntities;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The current salon's service photos (tenant filter and RLS). */
public interface ServicePhotoRepository extends JpaRepository<ServicePhoto, UUID>, NewEntities<ServicePhoto> {

    /** A photo by its id, through a query so that the tenant filter applies as it does to every read. */
    @Query("select p from ServicePhoto p where p.id = :id")
    Optional<ServicePhoto> findPhoto(@Param("id") UUID photoId);

    /** Removes a photo no service holds any longer. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from ServicePhoto p where p.id = :id")
    int remove(@Param("id") UUID photoId);
}
