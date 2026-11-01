package com.salonplatform.staff.repository;

import com.salonplatform.shared.persistence.NewEntities;
import com.salonplatform.staff.entity.MemberWeekdayBreak;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The breaks of the current salon's members' weeks (tenant filter and RLS). */
public interface MemberWeekdayBreakRepository
        extends JpaRepository<MemberWeekdayBreak, UUID>, NewEntities<MemberWeekdayBreak> {

    /** The rows of these members, in one query. */
    @Query("select r from MemberWeekdayBreak r where r.memberId in :members")
    List<MemberWeekdayBreak> ofMembers(@Param("members") Collection<UUID> memberIds);

    /** Removes a member's rows, which a save then replaces. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from MemberWeekdayBreak r where r.memberId = :member")
    int removeOf(@Param("member") UUID memberId);
}
