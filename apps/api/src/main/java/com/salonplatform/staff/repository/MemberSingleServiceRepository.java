package com.salonplatform.staff.repository;

import com.salonplatform.shared.persistence.NewEntities;
import com.salonplatform.staff.entity.MemberSingleService;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The single services the current salon's members perform (tenant filter and RLS). */
public interface MemberSingleServiceRepository
        extends JpaRepository<MemberSingleService, UUID>, NewEntities<MemberSingleService> {

    /** The rows of these members, in one query. */
    @Query("select r from MemberSingleService r where r.memberId in :members")
    List<MemberSingleService> ofMembers(@Param("members") Collection<UUID> memberIds);

    /** Removes a member's rows, which a save then replaces. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from MemberSingleService r where r.memberId = :member")
    int removeOf(@Param("member") UUID memberId);
}
