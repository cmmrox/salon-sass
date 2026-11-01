package com.salonplatform.staff.repository;

import com.salonplatform.shared.persistence.NewEntities;
import com.salonplatform.staff.entity.Closure;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The current salon's special closures (tenant filter and RLS); another salon's is simply not found. */
public interface ClosureRepository extends JpaRepository<Closure, UUID>, NewEntities<Closure> {

    /** The branch's closures from {@code from} to {@code to}, both included, in date order. */
    @Query("select c from Closure c where c.branchId = :branch and c.closureDate between :from and :to"
            + " order by c.closureDate")
    List<Closure> findBetween(@Param("branch") UUID branchId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
