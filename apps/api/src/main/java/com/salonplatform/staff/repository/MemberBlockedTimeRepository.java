package com.salonplatform.staff.repository;

import com.salonplatform.shared.persistence.NewEntities;
import com.salonplatform.staff.entity.MemberBlockedTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The current salon's members' blocked time (tenant filter and RLS). */
public interface MemberBlockedTimeRepository
        extends JpaRepository<MemberBlockedTime, UUID>, NewEntities<MemberBlockedTime> {

    /** A member's blocks from this date on, in date and time order. */
    @Query("select b from MemberBlockedTime b where b.memberId = :member and b.blockDate >= :from"
            + " order by b.blockDate, b.starts")
    List<MemberBlockedTime> upcoming(@Param("member") UUID memberId, @Param("from") LocalDate from);

    /** A member's blocks in a range of dates, both included. */
    @Query("select b from MemberBlockedTime b where b.memberId = :member and b.blockDate between :from and :to")
    List<MemberBlockedTime> between(
            @Param("member") UUID memberId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select count(b) from MemberBlockedTime b where b.memberId = :member and b.blockDate >= :from")
    long countUpcoming(@Param("member") UUID memberId, @Param("from") LocalDate from);

    /** Whether a block of the member's overlaps this stretch of the date; one that only touches it does not. */
    @Query("select count(b) > 0 from MemberBlockedTime b where b.memberId = :member and b.blockDate = :date"
            + " and b.starts < :finishes and b.finishes > :starts")
    boolean overlaps(
            @Param("member") UUID memberId,
            @Param("date") LocalDate date,
            @Param("starts") LocalTime starts,
            @Param("finishes") LocalTime finishes);

    /** A block of this member, through a query so that the tenant filter applies as it does to every read. */
    @Query("select b from MemberBlockedTime b where b.id = :id and b.memberId = :member")
    Optional<MemberBlockedTime> findOf(@Param("id") UUID id, @Param("member") UUID memberId);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from MemberBlockedTime b where b.id = :id")
    int removeById(@Param("id") UUID id);
}
