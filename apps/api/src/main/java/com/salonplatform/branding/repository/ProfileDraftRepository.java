package com.salonplatform.branding.repository;

import com.salonplatform.branding.entity.ProfileDraft;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** The current salon's profile draft: the one row the tenant filter and RLS leave. */
public interface ProfileDraftRepository extends JpaRepository<ProfileDraft, UUID> {

    /** The salon's draft, to read. */
    @Query("select d from ProfileDraft d")
    Optional<ProfileDraft> findCurrent();

    /**
     * The salon's draft, held for update until the transaction ends: every save, publication and discard
     * takes it first, so writers of the same draft go one at a time and each sees the version the last
     * one left.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from ProfileDraft d")
    Optional<ProfileDraft> lockCurrent();
}
