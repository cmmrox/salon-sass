package com.salonplatform.catalogue.service.impl;

import com.salonplatform.catalogue.repository.CataloguePlatformFunctions;
import com.salonplatform.platform.inprocess.SalonBootstrapStep;
import com.salonplatform.shared.persistence.PlatformDatabase;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * A new salon starts with the prototype's ten categories on its default branch (decisions 092, 093), written by
 * catalogue's own {@code catalogue_add_default_categories} on the platform connection, in the platform transaction
 * that creates the salon, so that the salon and its categories exist together or not at all.
 */
@Service
@Order(100)
class SalonBootstrapStepImpl implements SalonBootstrapStep {

    private final ObjectProvider<PlatformDatabase> platform;
    private final CataloguePlatformFunctions functions;

    SalonBootstrapStepImpl(ObjectProvider<PlatformDatabase> platform, CataloguePlatformFunctions functions) {
        this.platform = platform;
        this.functions = functions;
    }

    @Override
    public void bootstrap(UUID tenantId, Instant at) {
        platform.getObject().requireTransaction();
        functions.addDefaultCategories(tenantId, at);
    }
}
