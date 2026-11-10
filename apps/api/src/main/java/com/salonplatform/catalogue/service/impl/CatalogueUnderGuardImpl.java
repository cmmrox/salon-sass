package com.salonplatform.catalogue.service.impl;

import com.salonplatform.catalogue.CatalogueListing;
import com.salonplatform.catalogue.inprocess.CatalogueUnderGuard;
import com.salonplatform.catalogue.repository.CategoryRepository;
import com.salonplatform.catalogue.repository.SalonServiceRepository;
import com.salonplatform.shared.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The listing as {@link CatalogueListingsImpl} reads it, in the caller's transaction (decision 119). */
@Service
class CatalogueUnderGuardImpl implements CatalogueUnderGuard {

    private final CategoryRepository categories;
    private final SalonServiceRepository services;

    CatalogueUnderGuardImpl(CategoryRepository categories, SalonServiceRepository services) {
        this.categories = categories;
        this.services = services;
    }

    @Override
    public CatalogueListing current(UUID branchId) {
        TenantContext.require();
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The catalogue is checked under the guard in the caller's transaction");
        }
        return CatalogueListingsImpl.listing(categories, services, branchId);
    }
}
