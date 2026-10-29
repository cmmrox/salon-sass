package com.salonplatform.identity.repository;

import com.salonplatform.identity.entity.CustomerAccount;
import com.salonplatform.shared.persistence.NewEntities;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The current salon's customers: Hibernate's tenant filter and row-level security see to that. */
public interface CustomerAccountRepository extends JpaRepository<CustomerAccount, UUID>, NewEntities<CustomerAccount> {

    /** The customer of a verified number, unique in the salon. */
    Optional<CustomerAccount> findFirstByMobile(String mobile);
}
