package com.salonplatform.branding.repository;

import com.salonplatform.branding.entity.Branch;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** The current salon's branches. Every query is the current salon's only: Hibernate's tenant filter and RLS. */
public interface BranchRepository extends JpaRepository<Branch, UUID> {

    /** The salon's default branch. */
    @Query("select b from Branch b where b.isDefault = true")
    Optional<Branch> findDefault();

    /** Every branch of the salon, the default first, then by creation and id. */
    @Query("select b from Branch b order by b.isDefault desc, b.createdAt, b.id")
    List<Branch> findAllInOrder();
}
