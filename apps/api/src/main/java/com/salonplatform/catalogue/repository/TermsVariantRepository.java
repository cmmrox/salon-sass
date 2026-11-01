package com.salonplatform.catalogue.repository;

import com.salonplatform.catalogue.entity.TermsVariant;
import com.salonplatform.shared.persistence.NewEntities;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The current salon's terms variants (tenant filter and RLS). Written once with their version, through
 * {@link NewEntities#persist}; nothing here changes or removes one.
 */
public interface TermsVariantRepository extends JpaRepository<TermsVariant, UUID>, NewEntities<TermsVariant> {

    /** The variants of these versions, in one query, each version's in its order. */
    @Query("select v from TermsVariant v where v.termsVersionId in :versions order by v.termsVersionId, v.position")
    List<TermsVariant> findOfVersions(@Param("versions") Collection<UUID> termsVersionIds);
}
