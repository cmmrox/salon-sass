package com.salonplatform.staff.repository;

import com.salonplatform.shared.persistence.NewEntities;
import com.salonplatform.staff.entity.OpeningHours;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The current salon's weekly opening hours, one row per branch (tenant filter and RLS). */
public interface OpeningHoursRepository extends JpaRepository<OpeningHours, UUID>, NewEntities<OpeningHours> {

    @Query("select h from OpeningHours h where h.branchId = :branch")
    Optional<OpeningHours> findByBranch(@Param("branch") UUID branchId);

    /** The branch's hours, held for update until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from OpeningHours h where h.branchId = :branch")
    Optional<OpeningHours> lockByBranch(@Param("branch") UUID branchId);
}
