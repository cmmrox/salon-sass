package com.salonplatform.catalogue.service.impl;

import com.salonplatform.catalogue.Product;
import com.salonplatform.catalogue.Products;
import com.salonplatform.catalogue.entity.SalonProduct;
import com.salonplatform.catalogue.repository.SalonProductRepository;
import com.salonplatform.catalogue.service.impl.CatalogueScope.Scope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads products in a read-only tenant transaction of its own; another salon's product is not seen, because the
 * tenant filter and row-level security both hide it, and a deleted product's row is gone.
 */
@Service
class ProductsImpl implements Products {

    private final CatalogueScope scope;
    private final SalonProductRepository products;
    private final TransactionTemplate reading;

    ProductsImpl(CatalogueScope scope, SalonProductRepository products, PlatformTransactionManager transactions) {
        this.scope = scope;
        this.products = products;
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
        this.reading.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Optional<Product> product(UUID id) {
        Scope at = scope.publicly();
        if (id == null) {
            return Optional.empty();
        }
        return reading.execute(status -> products.findInBranch(id, at.branch()).map(SalonProduct::product));
    }

    @Override
    public List<Product> soldAtCheckout() {
        Scope at = scope.publicly();
        return reading.execute(status -> products.soldByName(at.branch()).stream()
                .map(SalonProduct::product)
                .toList());
    }
}
