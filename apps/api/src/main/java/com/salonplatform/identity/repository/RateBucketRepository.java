package com.salonplatform.identity.repository;

import com.salonplatform.identity.entity.RateBucket;
import com.salonplatform.shared.persistence.NewEntities;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The current salon's rate buckets: Hibernate's tenant filter and row-level security see to that. */
public interface RateBucketRepository extends JpaRepository<RateBucket, UUID>, NewEntities<RateBucket> {

    Optional<RateBucket> findFirstByDimensionAndKeyHash(String dimension, String keyHash);
}
