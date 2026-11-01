package com.salonplatform.catalogue.repository;

import com.salonplatform.catalogue.entity.PopularSetting;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The current salon's Popular settings (tenant filter and RLS). */
public interface PopularSettingRepository extends JpaRepository<PopularSetting, UUID> {

    @Query("select p from PopularSetting p where p.branchId = :branch")
    Optional<PopularSetting> findOfBranch(@Param("branch") UUID branchId);

    /**
     * The branch's setting, locked until the transaction ends: for a change of the setting and for a choice of a
     * Popular service, so that two choices of a sixth service wait for each other and the limit holds.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PopularSetting p where p.branchId = :branch")
    Optional<PopularSetting> lock(@Param("branch") UUID branchId);
}
