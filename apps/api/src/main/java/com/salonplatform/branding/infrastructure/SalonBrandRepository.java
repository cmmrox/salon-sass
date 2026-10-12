package com.salonplatform.branding.infrastructure;

import java.util.Optional;
import java.util.UUID;

import com.salonplatform.branding.domain.SalonBrand;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Salon brands. Every query is the current salon's only: Hibernate's tenant filter and row-level security. */
public interface SalonBrandRepository extends JpaRepository<SalonBrand, UUID> {

    /** The current salon's brand: the one row the tenant filter leaves. */
    @Query("select b from SalonBrand b")
    Optional<SalonBrand> findCurrent();
}
