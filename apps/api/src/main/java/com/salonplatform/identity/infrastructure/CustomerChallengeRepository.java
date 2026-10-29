package com.salonplatform.identity.infrastructure;

import com.salonplatform.identity.domain.CustomerChallenge;
import com.salonplatform.shared.persistence.NewEntities;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The current salon's customer codes: Hibernate's tenant filter and row-level security see to that. */
public interface CustomerChallengeRepository
        extends JpaRepository<CustomerChallenge, UUID>, NewEntities<CustomerChallenge> {

    /** The codes of one number that a newer request must invalidate. */
    List<CustomerChallenge> findByNumberHashAndInvalidatedAtIsNullAndConsumedAtIsNull(String numberHash);

    List<CustomerChallenge> findByNumberHash(String numberHash);

    List<CustomerChallenge> findByExpiresAtLessThanEqual(Instant now);
}
