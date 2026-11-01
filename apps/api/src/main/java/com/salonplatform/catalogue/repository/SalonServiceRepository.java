package com.salonplatform.catalogue.repository;

import com.salonplatform.catalogue.entity.SalonService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The current salon's services (tenant filter and RLS); a deleted one is read only by {@link #findAny}. */
public interface SalonServiceRepository extends JpaRepository<SalonService, UUID> {

    /** How many live services a category has. */
    record CategoryCount(UUID categoryId, long services) {}

    /** The branch's live services, by name. */
    @Query("select s from SalonService s where s.branchId = :branch and s.deletedAt is null order by lower(s.name)")
    List<SalonService> live(@Param("branch") UUID branchId);

    /** The branch's live services that are bookable online, by name. */
    @Query("select s from SalonService s where s.branchId = :branch and s.deletedAt is null and s.bookableOnline"
            + " order by lower(s.name), s.name")
    List<SalonService> bookableOnline(@Param("branch") UUID branchId);

    /** A live service of the branch by its id. */
    @Query("select s from SalonService s where s.id = :id and s.branchId = :branch and s.deletedAt is null")
    Optional<SalonService> findLive(@Param("id") UUID id, @Param("branch") UUID branchId);

    /** A service by its id, deleted or not. */
    @Query("select s from SalonService s where s.id = :id")
    Optional<SalonService> findAny(@Param("id") UUID id);

    /** Services by their ids, deleted or not. */
    @Query("select s from SalonService s where s.id in :ids")
    List<SalonService> findAllOf(@Param("ids") List<UUID> ids);

    @Query("select count(s) from SalonService s where s.branchId = :branch and s.deletedAt is null")
    long countLive(@Param("branch") UUID branchId);

    /** The live services each of the branch's categories has; a category with none is absent. */
    @Query("select new com.salonplatform.catalogue.repository.SalonServiceRepository$CategoryCount(s.categoryId,"
            + " count(s)) from SalonService s where s.branchId = :branch and s.deletedAt is null"
            + " group by s.categoryId")
    List<CategoryCount> countsByCategory(@Param("branch") UUID branchId);

    @Query("select count(s) from SalonService s where s.categoryId = :category and s.deletedAt is null")
    long countInCategory(@Param("category") UUID categoryId);

    /** The branch's live service names in lower case. */
    @Query("select lower(s.name) from SalonService s where s.branchId = :branch and s.deletedAt is null")
    List<String> liveNames(@Param("branch") UUID branchId);

    /** Whether another live service of the branch already has this name, regardless of case. */
    @Query("select count(s) > 0 from SalonService s where s.branchId = :branch and s.deletedAt is null"
            + " and lower(s.name) = lower(:name) and (:except is null or s.id <> :except)")
    boolean nameTaken(@Param("branch") UUID branchId, @Param("name") String name, @Param("except") UUID except);
}
