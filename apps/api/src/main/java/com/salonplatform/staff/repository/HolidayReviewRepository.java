package com.salonplatform.staff.repository;

import com.salonplatform.shared.persistence.NewEntities;
import com.salonplatform.staff.entity.HolidayReview;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The current salon's holiday reviews, one per branch and year (tenant filter and RLS). */
public interface HolidayReviewRepository extends JpaRepository<HolidayReview, UUID>, NewEntities<HolidayReview> {

    @Query("select r from HolidayReview r where r.branchId = :branch and r.year in :years")
    List<HolidayReview> findForYears(@Param("branch") UUID branchId, @Param("years") Collection<Integer> years);

    /** The branch's review of the year, held for update until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from HolidayReview r where r.branchId = :branch and r.year = :year")
    Optional<HolidayReview> lock(@Param("branch") UUID branchId, @Param("year") int year);
}
