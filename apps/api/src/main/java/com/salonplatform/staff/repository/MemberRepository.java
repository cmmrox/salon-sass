package com.salonplatform.staff.repository;

import com.salonplatform.staff.entity.Member;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The current salon's team members (tenant filter and RLS); a removed one is read only by {@link #findAny}. */
public interface MemberRepository extends JpaRepository<Member, UUID> {

    /** The branch's members still on the team, in name order. */
    @Query("select m from Member m where m.branchId = :branch and m.removedAt is null order by lower(m.name), m.name,"
            + " m.id")
    List<Member> active(@Param("branch") UUID branchId);

    /** A member still on the team, of the branch. */
    @Query("select m from Member m where m.id = :id and m.branchId = :branch and m.removedAt is null")
    Optional<Member> findActive(@Param("id") UUID id, @Param("branch") UUID branchId);

    /** A member by id, removed or not, through a query so that the tenant filter applies as it does to every read. */
    @Query("select m from Member m where m.id = :id")
    Optional<Member> findAny(@Param("id") UUID id);

    /** The member still on the team who holds this account. */
    @Query("select m from Member m where m.signInAccountId = :account and m.removedAt is null")
    Optional<Member> findLinkedTo(@Param("account") UUID accountId);

    /** How many members are on the team, in every branch. */
    @Query("select count(m) from Member m where m.removedAt is null")
    long countActive();

    /** The members on the team with no linked account, in every branch: one staff seat each. */
    @Query("select count(m) from Member m where m.removedAt is null and m.signInAccountId is null")
    long countActiveWithoutAccount();
}
