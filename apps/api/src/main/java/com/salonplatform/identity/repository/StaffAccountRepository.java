package com.salonplatform.identity.repository;

import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.identity.entity.StaffAccount;
import com.salonplatform.shared.persistence.NewEntities;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The current salon's staff accounts: Hibernate's tenant filter and row-level security see to that. */
public interface StaffAccountRepository extends JpaRepository<StaffAccount, UUID>, NewEntities<StaffAccount> {

    /** The account a sign-in identifier names: its email or its mobile number, both unique in the salon. */
    @Query("select a from StaffAccount a where a.email = :identifier or a.mobile = :identifier")
    List<StaffAccount> findByIdentifier(@Param("identifier") String identifier);

    /** Accounts holding either identifier; a null identifier matches nothing. */
    @Query("""
            select a from StaffAccount a
             where (:email is not null and a.email = :email) or (:mobile is not null and a.mobile = :mobile)
            """)
    List<StaffAccount> findHolding(@Param("email") String email, @Param("mobile") String mobile);

    /** How many accounts hold either identifier; a null identifier matches nothing. */
    @Query("""
            select count(a) from StaffAccount a
             where (:email is not null and a.email = :email) or (:mobile is not null and a.mobile = :mobile)
            """)
    long countHolding(@Param("email") String email, @Param("mobile") String mobile);

    long countByActiveTrue();

    long countByActiveTrueAndRole(StaffRole role);
}
