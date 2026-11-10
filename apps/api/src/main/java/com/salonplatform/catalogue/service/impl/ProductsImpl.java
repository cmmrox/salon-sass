package com.salonplatform.catalogue.service.impl;

import com.salonplatform.catalogue.Product;
import com.salonplatform.catalogue.Products;
import com.salonplatform.catalogue.entity.SalonProduct;
import com.salonplatform.catalogue.repository.SalonProductRepository;
import com.salonplatform.catalogue.service.impl.CatalogueScope.Scope;
import com.salonplatform.shared.persistence.ReadOnlyWork;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Reads products in a read-only tenant transaction of its own; another salon's product is not seen, because the
 * tenant filter and row-level security both hide it, and a deleted product's row is gone.
 */
@Service
class ProductsImpl implements Products {

    private final CatalogueScope scope;
    private final SalonProductRepository products;
    private final ReadOnlyWork reading;

    ProductsImpl(CatalogueScope scope, SalonProductRepository products, ReadOnlyWork reading) {
        this.scope = scope;
        this.products = products;
        this.reading = reading;
    }

    @Override
    public Optional<Product> product(UUID id) {
        Scope at = scope.publicly();
        if (id == null) {
            return Optional.empty();
        }
        return reading.read(() -> products.findInBranch(id, at.branch()).map(SalonProduct::product));
    }

    @Override
    public List<Product> soldAtCheckout() {
        Scope at = scope.publicly();
        return reading.read(() -> products.soldByName(at.branch()).stream()
                .map(SalonProduct::product)
                .toList());
    }
}
