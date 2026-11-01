package com.salonplatform.staff.repository;

import com.salonplatform.shared.persistence.NewEntities;
import com.salonplatform.staff.entity.MemberCategory;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The whole categories the current salon's members perform (tenant filter and RLS). */
public interface MemberCategoryRepository extends JpaRepository<MemberCategory, UUID>, NewEntities<MemberCategory> {

    /** The rows of these members, in one query. */
    @Query("select r from MemberCategory r where r.memberId in :members")
    List<MemberCategory> ofMembers(@Param("members") Collection<UUID> memberIds);

    /** Removes a member's rows, which a save then replaces. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from MemberCategory r where r.memberId = :member")
    int removeOf(@Param("member") UUID memberId);
}
