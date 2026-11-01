package com.salonplatform.catalogue.repository;

import com.salonplatform.catalogue.entity.Category;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The current salon's categories (tenant filter and RLS). */
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    /** The branch's categories in the salon's order. */
    @Query("select c from Category c where c.branchId = :branch order by c.position")
    List<Category> inOrder(@Param("branch") UUID branchId);

    /** A category by its id, through a query so that the tenant filter applies as it does to every read. */
    @Query("select c from Category c where c.id = :id and c.branchId = :branch")
    Optional<Category> findInBranch(@Param("id") UUID id, @Param("branch") UUID branchId);

    /** Whether another category of the branch already has this name, regardless of case. */
    @Query("select count(c) > 0 from Category c where c.branchId = :branch and lower(c.name) = lower(:name)"
            + " and (:except is null or c.id <> :except)")
    boolean nameTaken(@Param("branch") UUID branchId, @Param("name") String name, @Param("except") UUID except);
}
