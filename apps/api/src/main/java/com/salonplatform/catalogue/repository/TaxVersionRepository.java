package com.salonplatform.catalogue.repository;

import com.salonplatform.catalogue.entity.TaxVersion;
import com.salonplatform.shared.persistence.NewEntities;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The current salon's tax settings versions (tenant filter and RLS). Versions are only ever inserted, through
 * {@link NewEntities#persist}; nothing here changes or removes one.
 */
public interface TaxVersionRepository extends JpaRepository<TaxVersion, UUID>, NewEntities<TaxVersion> {

    /** The branch's newest version: its current settings. */
    Optional<TaxVersion> findFirstByBranchIdOrderByVersionDesc(UUID branchId);

    /** A version by its id, through a query so that the tenant filter applies as it does to every read. */
    @Query("select v from TaxVersion v where v.id = :id")
    Optional<TaxVersion> findVersion(@Param("id") UUID versionId);

    /** The branch's versions, newest first. */
    @Query(
            value = "select v from TaxVersion v where v.branchId = :branch order by v.version desc",
            countQuery = "select count(v) from TaxVersion v where v.branchId = :branch")
    Page<TaxVersion> history(@Param("branch") UUID branchId, Pageable page);
}
