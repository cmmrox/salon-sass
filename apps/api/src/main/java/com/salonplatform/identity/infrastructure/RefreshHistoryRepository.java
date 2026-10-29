package com.salonplatform.identity.infrastructure;

import com.salonplatform.identity.domain.RefreshHistory;
import com.salonplatform.shared.persistence.NewEntities;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The current salon's spent refresh credentials: Hibernate's tenant filter and row-level security. */
public interface RefreshHistoryRepository extends JpaRepository<RefreshHistory, UUID>, NewEntities<RefreshHistory> {

    Optional<RefreshHistory> findFirstByCredentialHash(String credentialHash);
}
