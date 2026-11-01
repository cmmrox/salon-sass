package com.salonplatform.branding.repository;

import com.salonplatform.branding.entity.PublishedProfile;
import com.salonplatform.shared.persistence.NewEntities;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** The current salon's published profile, when it has published one (tenant filter and RLS). */
public interface PublishedProfileRepository
        extends JpaRepository<PublishedProfile, UUID>, NewEntities<PublishedProfile> {

    @Query("select p from PublishedProfile p")
    Optional<PublishedProfile> findCurrent();
}
