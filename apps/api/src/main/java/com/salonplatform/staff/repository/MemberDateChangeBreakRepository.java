package com.salonplatform.staff.repository;

import com.salonplatform.shared.persistence.NewEntities;
import com.salonplatform.staff.entity.MemberDateChangeBreak;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The breaks of the current salon's members' date changes (tenant filter and RLS). */
public interface MemberDateChangeBreakRepository
        extends JpaRepository<MemberDateChangeBreak, UUID>, NewEntities<MemberDateChangeBreak> {

    /** The rows of these changes, in one query. */
    @Query("select r from MemberDateChangeBreak r where r.changeId in :changes")
    List<MemberDateChangeBreak> ofChanges(@Param("changes") Collection<UUID> changeIds);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from MemberDateChangeBreak r where r.changeId = :change")
    int removeOf(@Param("change") UUID changeId);
}
