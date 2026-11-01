package com.salonplatform.catalogue.repository;

import com.salonplatform.catalogue.entity.CategoryList;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The current salon's category lists (tenant filter and RLS). */
public interface CategoryListRepository extends JpaRepository<CategoryList, UUID> {

    @Query("select l from CategoryList l where l.branchId = :branch")
    Optional<CategoryList> findOfBranch(@Param("branch") UUID branchId);

    /**
     * The branch's list, locked until the transaction ends: for an add, delete or move of a category and for a new
     * service, so that two such changes wait for each other, the limits hold and the list version is exact.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from CategoryList l where l.branchId = :branch")
    Optional<CategoryList> lock(@Param("branch") UUID branchId);
}
