package com.salonplatform.catalogue.repository;

import com.salonplatform.catalogue.entity.TermsVersion;
import com.salonplatform.shared.persistence.NewEntities;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The current salon's terms versions (tenant filter and RLS). Versions are only ever inserted, through
 * {@link NewEntities#persist}; nothing here changes or removes one.
 */
public interface TermsVersionRepository extends JpaRepository<TermsVersion, UUID>, NewEntities<TermsVersion> {

    /** A version by its id, through a query so that the tenant filter applies as it does to every read. */
    @Query("select v from TermsVersion v where v.id = :id")
    Optional<TermsVersion> findVersion(@Param("id") UUID versionId);

    /** Versions by their ids, in one query. */
    @Query("select v from TermsVersion v where v.id in :ids")
    List<TermsVersion> findVersions(@Param("ids") Collection<UUID> versionIds);
}
