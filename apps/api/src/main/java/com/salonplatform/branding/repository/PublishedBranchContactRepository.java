package com.salonplatform.branding.repository;

import com.salonplatform.branding.entity.PublishedBranchContact;
import com.salonplatform.shared.persistence.NewEntities;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** The published copies of the current salon's branch contact details (tenant filter and RLS). */
public interface PublishedBranchContactRepository
        extends JpaRepository<PublishedBranchContact, UUID>, NewEntities<PublishedBranchContact> {

    @Query("select c from PublishedBranchContact c order by c.branchId")
    List<PublishedBranchContact> findAllInOrder();
}
