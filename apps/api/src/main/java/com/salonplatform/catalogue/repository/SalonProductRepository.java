package com.salonplatform.catalogue.repository;

import com.salonplatform.catalogue.entity.SalonProduct;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The current salon's products (tenant filter and RLS). */
public interface SalonProductRepository extends JpaRepository<SalonProduct, UUID> {

    /** The branch's products by name regardless of case: at most 300, so the list is read whole. */
    @Query("select p from SalonProduct p where p.branchId = :branch order by lower(p.name), p.id")
    List<SalonProduct> byName(@Param("branch") UUID branchId);

    /** The branch's products sold at checkout, by name regardless of case. */
    @Query("select p from SalonProduct p where p.branchId = :branch and p.soldAtCheckout = true"
            + " order by lower(p.name), p.id")
    List<SalonProduct> soldByName(@Param("branch") UUID branchId);

    /** A product by its id, through a query so that the tenant filter applies as it does to every read. */
    @Query("select p from SalonProduct p where p.id = :id and p.branchId = :branch")
    Optional<SalonProduct> findInBranch(@Param("id") UUID id, @Param("branch") UUID branchId);

    @Query("select count(p) from SalonProduct p where p.branchId = :branch")
    long countInBranch(@Param("branch") UUID branchId);

    /** Whether another product of the branch already has this name, regardless of case. */
    @Query("select count(p) > 0 from SalonProduct p where p.branchId = :branch and lower(p.name) = lower(:name)"
            + " and (:except is null or p.id <> :except)")
    boolean nameTaken(@Param("branch") UUID branchId, @Param("name") String name, @Param("except") UUID except);
}
