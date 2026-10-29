package com.salonplatform.identity.infrastructure;

import com.salonplatform.identity.domain.MfaStep;
import com.salonplatform.shared.persistence.NewEntities;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The current salon's used authenticator steps: Hibernate's tenant filter and row-level security. */
public interface MfaStepRepository extends JpaRepository<MfaStep, UUID>, NewEntities<MfaStep> {

    boolean existsByAccountIdAndStep(UUID accountId, long step);
}
