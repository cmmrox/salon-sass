package com.salonplatform.catalogue.service.impl;

import com.salonplatform.catalogue.CatalogueListing;
import com.salonplatform.catalogue.CatalogueListings;
import com.salonplatform.catalogue.repository.CategoryRepository;
import com.salonplatform.catalogue.repository.SalonServiceRepository;
import com.salonplatform.catalogue.service.impl.CatalogueScope.Scope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Two reads in one read-only transaction: the categories in order, and the live services by name. */
@Service
class CatalogueListingsImpl implements CatalogueListings {

    private final CatalogueScope scope;
    private final CategoryRepository categories;
    private final SalonServiceRepository services;
    private final TransactionTemplate reading;

    CatalogueListingsImpl(
            CatalogueScope scope,
            CategoryRepository categories,
            SalonServiceRepository services,
            PlatformTransactionManager transactions) {
        this.scope = scope;
        this.categories = categories;
        this.services = services;
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
    }

    @Override
    public CatalogueListing current() {
        Scope at = scope.publicly();
        return reading.execute(status -> new CatalogueListing(
                categories.inOrder(at.branch()).stream()
                        .map(category -> new CatalogueListing.Category(category.getId(), category.getName()))
                        .toList(),
                services.live(at.branch()).stream()
                        .map(service -> new CatalogueListing.Service(
                                service.getId(),
                                service.getCategoryId(),
                                service.getName(),
                                service.isBookableOnline()))
                        .toList()));
    }
}
