package com.salonplatform.identity.infrastructure;

import com.salonplatform.identity.domain.MfaChallenge;
import com.salonplatform.shared.persistence.NewEntities;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The current salon's second-step challenges: Hibernate's tenant filter and row-level security. */
public interface MfaChallengeRepository extends JpaRepository<MfaChallenge, UUID>, NewEntities<MfaChallenge> {}
