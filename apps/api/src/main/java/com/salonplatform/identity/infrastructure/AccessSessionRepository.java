package com.salonplatform.identity.infrastructure;

import com.salonplatform.identity.domain.AccessSession;
import com.salonplatform.shared.persistence.NewEntities;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The current salon's sessions: Hibernate's tenant filter and row-level security see to that. */
public interface AccessSessionRepository extends JpaRepository<AccessSession, UUID>, NewEntities<AccessSession> {

    Optional<AccessSession> findFirstByRefreshHash(String refreshHash);

    /** A staff member's sessions that are neither revoked nor expired, newest first. */
    @Query("""
            select s from AccessSession s
             where s.accountId = :owner and s.kind = 'staff' and s.revokedAt is null and s.expiresAt > :now
             order by s.createdAt desc, s.id
            """)
    Page<AccessSession> findLiveOfStaff(@Param("owner") UUID owner, @Param("now") Instant now, Pageable page);

    /** A customer's sessions that are neither revoked nor expired, newest first. */
    @Query("""
            select s from AccessSession s
             where s.customerAccountId = :owner and s.kind = 'customer' and s.revokedAt is null and s.expiresAt > :now
             order by s.createdAt desc, s.id
            """)
    Page<AccessSession> findLiveOfCustomer(@Param("owner") UUID owner, @Param("now") Instant now, Pageable page);

    List<AccessSession> findByKindAndAccountIdAndRevokedAtIsNull(String kind, UUID accountId);

    List<AccessSession> findByKindAndCustomerAccountIdAndRevokedAtIsNull(String kind, UUID customerAccountId);
}
