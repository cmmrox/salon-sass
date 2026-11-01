package com.salonplatform.booking.repository;

import com.salonplatform.booking.entity.BookingRulesVersion;
import com.salonplatform.shared.persistence.NewEntities;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The current salon's booking rule versions (tenant filter and RLS). Versions are only ever inserted, through
 * {@link NewEntities#persist}; nothing here changes or removes one.
 */
public interface BookingRulesVersionRepository
        extends JpaRepository<BookingRulesVersion, UUID>, NewEntities<BookingRulesVersion> {

    /** The branch's newest version: its current rules. */
    Optional<BookingRulesVersion> findFirstByBranchIdOrderByVersionDesc(UUID branchId);

    /** A version by its id, through a query so that the tenant filter applies as it does to every read. */
    @Query("select v from BookingRulesVersion v where v.id = :id")
    Optional<BookingRulesVersion> findVersion(@Param("id") UUID versionId);

    /** The branch's versions, newest first. */
    @Query(
            value = "select v from BookingRulesVersion v where v.branchId = :branch order by v.version desc",
            countQuery = "select count(v) from BookingRulesVersion v where v.branchId = :branch")
    Page<BookingRulesVersion> history(@Param("branch") UUID branchId, Pageable page);
}
