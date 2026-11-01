package com.salonplatform.staff.repository;

import com.salonplatform.shared.persistence.NewEntities;
import com.salonplatform.staff.entity.HolidayChoice;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The current salon's holiday choices (tenant filter and RLS). */
public interface HolidayChoiceRepository extends JpaRepository<HolidayChoice, UUID>, NewEntities<HolidayChoice> {

    /** The branch's choices for the dates from {@code from} to {@code to}, both included. */
    @Query("select c from HolidayChoice c where c.branchId = :branch and c.holidayDate between :from and :to"
            + " order by c.holidayDate")
    List<HolidayChoice> findBetween(
            @Param("branch") UUID branchId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
