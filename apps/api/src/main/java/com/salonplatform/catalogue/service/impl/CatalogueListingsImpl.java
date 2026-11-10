package com.salonplatform.catalogue.service.impl;

import com.salonplatform.catalogue.CatalogueListing;
import com.salonplatform.catalogue.CatalogueListings;
import com.salonplatform.catalogue.repository.CategoryRepository;
import com.salonplatform.catalogue.repository.SalonServiceRepository;
import com.salonplatform.catalogue.service.impl.CatalogueScope.Scope;
import com.salonplatform.shared.persistence.ReadOnlyWork;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Two reads in one read-only transaction: the categories in order, and the live services by name. */
@Service
class CatalogueListingsImpl implements CatalogueListings {

    private final CatalogueScope scope;
    private final CategoryRepository categories;
    private final SalonServiceRepository services;
    private final ReadOnlyWork reading;

    CatalogueListingsImpl(
            CatalogueScope scope,
            CategoryRepository categories,
            SalonServiceRepository services,
            ReadOnlyWork reading) {
        this.scope = scope;
        this.categories = categories;
        this.services = services;
        this.reading = reading;
    }

    @Override
    public CatalogueListing current() {
        Scope at = scope.publicly();
        return reading.read(() -> listing(categories, services, at.branch()));
    }

    /** The branch's categories in order and its live services by name, read in the transaction that is open. */
    static CatalogueListing listing(CategoryRepository categories, SalonServiceRepository services, UUID branch) {
        return new CatalogueListing(
                categories.inOrder(branch).stream()
                        .map(category -> new CatalogueListing.Category(category.getId(), category.getName()))
                        .toList(),
                services.live(branch).stream()
                        .map(service -> new CatalogueListing.Service(
                                service.getId(),
                                service.getCategoryId(),
                                service.getName(),
                                service.isBookableOnline()))
                        .toList());
    }
}
