package com.salonplatform.branding.repository;

import com.salonplatform.branding.entity.BranchContactDraft;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** The drafts of the current salon's branch contact details (tenant filter and RLS). */
public interface BranchContactDraftRepository extends JpaRepository<BranchContactDraft, UUID> {

    @Query("select c from BranchContactDraft c order by c.branchId")
    List<BranchContactDraft> findAllInOrder();
}
