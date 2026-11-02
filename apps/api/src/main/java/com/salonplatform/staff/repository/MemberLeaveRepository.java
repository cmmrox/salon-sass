package com.salonplatform.staff.repository;

import com.salonplatform.shared.persistence.NewEntities;
import com.salonplatform.staff.entity.MemberLeave;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The current salon's members' leave (tenant filter and RLS). */
public interface MemberLeaveRepository extends JpaRepository<MemberLeave, UUID>, NewEntities<MemberLeave> {

    /** A member's leave that has not ended by this date, in date order. */
    @Query("select l from MemberLeave l where l.memberId = :member and l.lastDay >= :from order by l.firstDay")
    List<MemberLeave> notEnded(@Param("member") UUID memberId, @Param("from") LocalDate from);

    /** A member's leave with any day in a range of dates, both included. */
    @Query("select l from MemberLeave l where l.memberId = :member and l.firstDay <= :to and l.lastDay >= :from")
    List<MemberLeave> overlapping(
            @Param("member") UUID memberId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select count(l) from MemberLeave l where l.memberId = :member and l.lastDay >= :from")
    long countNotEnded(@Param("member") UUID memberId, @Param("from") LocalDate from);

    /**
     * Each of these members' current or next leave: the earliest that has not ended by this date. One a member at most, as
     * one person's leaves do not overlap.
     */
    @Query("""
            select l from MemberLeave l where l.memberId in :members and l.firstDay = (
                select min(other.firstDay) from MemberLeave other
                where other.memberId = l.memberId and other.lastDay >= :from)
            """)
    List<MemberLeave> nextOf(@Param("members") Collection<UUID> memberIds, @Param("from") LocalDate from);

    /** Leave of this member, through a query so that the tenant filter applies as it does to every read. */
    @Query("select l from MemberLeave l where l.id = :id and l.memberId = :member")
    Optional<MemberLeave> findOf(@Param("id") UUID id, @Param("member") UUID memberId);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from MemberLeave l where l.id = :id")
    int removeById(@Param("id") UUID id);
}
