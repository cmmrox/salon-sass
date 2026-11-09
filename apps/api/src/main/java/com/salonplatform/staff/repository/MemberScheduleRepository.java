package com.salonplatform.staff.repository;

import com.salonplatform.shared.persistence.NewEntities;
import com.salonplatform.staff.entity.MemberSchedule;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The versions of the current salon's members' weeks, one row a member (tenant filter and RLS). */
public interface MemberScheduleRepository extends JpaRepository<MemberSchedule, UUID>, NewEntities<MemberSchedule> {

    @Query("select s from MemberSchedule s where s.memberId = :member")
    Optional<MemberSchedule> findOf(@Param("member") UUID memberId);
}
