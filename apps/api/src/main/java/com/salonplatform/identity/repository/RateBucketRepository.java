package com.salonplatform.identity.repository;

import com.salonplatform.identity.entity.RateBucket;
import com.salonplatform.shared.persistence.NewEntities;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/** The current salon's rate buckets: Hibernate's tenant filter and row-level security see to that. */
public interface RateBucketRepository extends JpaRepository<RateBucket, UUID>, NewEntities<RateBucket> {

    /**
     * The bucket, held for update until the transaction ends, so the security cleanup job cannot remove it between
     * this read and the attempt's update (BUG-F02-017): a removal already under way is waited for, and the bucket is
     * then absent, so the attempt opens a new window as for a first attempt.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RateBucket> findFirstByDimensionAndKeyHash(String dimension, String keyHash);
}
