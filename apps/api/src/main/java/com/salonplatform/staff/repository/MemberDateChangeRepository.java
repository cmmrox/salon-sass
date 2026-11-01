package com.salonplatform.staff.repository;

import com.salonplatform.shared.persistence.NewEntities;
import com.salonplatform.staff.entity.MemberDateChange;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The current salon's members' date changes (tenant filter and RLS). */
public interface MemberDateChangeRepository
        extends JpaRepository<MemberDateChange, UUID>, NewEntities<MemberDateChange> {

    /** A member's changes from this date on, in date order. */
    @Query(
            "select c from MemberDateChange c where c.memberId = :member and c.changeDate >= :from order by c.changeDate")
    List<MemberDateChange> upcoming(@Param("member") UUID memberId, @Param("from") LocalDate from);

    /** A member's changes in a range of dates, both included. */
    @Query("select c from MemberDateChange c where c.memberId = :member and c.changeDate between :from and :to")
    List<MemberDateChange> between(
            @Param("member") UUID memberId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select count(c) from MemberDateChange c where c.memberId = :member and c.changeDate >= :from")
    long countUpcoming(@Param("member") UUID memberId, @Param("from") LocalDate from);

    @Query("select count(c) > 0 from MemberDateChange c where c.memberId = :member and c.changeDate = :date")
    boolean existsOn(@Param("member") UUID memberId, @Param("date") LocalDate date);

    /** A change of this member, through a query so that the tenant filter applies as it does to every read. */
    @Query("select c from MemberDateChange c where c.id = :id and c.memberId = :member")
    Optional<MemberDateChange> findOf(@Param("id") UUID id, @Param("member") UUID memberId);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from MemberDateChange c where c.id = :id")
    int removeById(@Param("id") UUID id);
}
