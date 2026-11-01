package com.salonplatform.branding.repository;

import com.salonplatform.branding.entity.BrandDraft;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** The current salon's brand draft: the one row the tenant filter and RLS leave. */
public interface BrandDraftRepository extends JpaRepository<BrandDraft, UUID> {

    @Query("select d from BrandDraft d")
    Optional<BrandDraft> findCurrent();

    /** The salon's brand draft, held for update until the transaction ends, as the profile draft's is. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from BrandDraft d")
    Optional<BrandDraft> lockCurrent();
}
