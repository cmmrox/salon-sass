package com.salonplatform.identity.infrastructure;

import com.salonplatform.identity.domain.IdentityGuard;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** The current salon's identity lock row: Hibernate's tenant filter and row-level security see to that. */
public interface IdentityGuardRepository extends JpaRepository<IdentityGuard, UUID> {

    /** The salon's lock row, held for update until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from IdentityGuard g")
    Optional<IdentityGuard> lockCurrent();
}
