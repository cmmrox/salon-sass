package com.salonplatform.catalogue.service.impl;

import com.salonplatform.catalogue.domain.CatalogueLimits;
import com.salonplatform.catalogue.domain.MinorUnits;
import com.salonplatform.catalogue.entity.SalonProduct;
import com.salonplatform.catalogue.entity.SalonProduct.Details;
import com.salonplatform.catalogue.repository.CategoryListRepository;
import com.salonplatform.catalogue.repository.SalonProductRepository;
import com.salonplatform.catalogue.service.ProductService;
import com.salonplatform.catalogue.service.impl.CatalogueScope.Scope;
import com.salonplatform.platform.SalonCurrency;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.shared.web.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An add locks the branch's category list, the branch's one catalogue lock that a new service also takes, so two adds
 * wait for each other and the limit of 300 holds; its list version is not raised, because the categories did not
 * change. A save or delete is guarded by the product's own version. The unique name index is the database's backstop:
 * a lost race answers {@code conflict} and nothing is kept. The salon's currency is read before the transaction opens.
 */
@Service
class ProductServiceImpl implements ProductService {

    private static final String ADDED = "catalogue.product_added";
    private static final String UPDATED = "catalogue.product_updated";
    private static final String DELETED = "catalogue.product_deleted";

    private final CatalogueScope scope;
    private final CategoryListRepository lists;
    private final SalonProductRepository products;
    private final SalonCurrency currency;
    private final CatalogueAudit audit;
    private final Clock clock;
    private final TransactionTemplate writing;
    private final TransactionTemplate reading;

    ProductServiceImpl(
            CatalogueScope scope,
            CategoryListRepository lists,
            SalonProductRepository products,
            SalonCurrency currency,
            CatalogueAudit audit,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.scope = scope;
        this.lists = lists;
        this.products = products;
        this.currency = currency;
        this.audit = audit;
        this.clock = clock;
        this.writing = new TransactionTemplate(transactions);
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
    }

    @Override
    public ProductList list() {
        Scope at = scope.of(CatalogueScope.READ);
        String salonCurrency = currency.current();
        return new ProductList(
                salonCurrency,
                reading.execute(status -> products.byName(at.branch()).stream()
                        .map(ProductServiceImpl::view)
                        .toList()));
    }

    @Override
    public ProductView add(ProductInput input) {
        Scope at = scope.of(CatalogueScope.CHANGE);
        Details details = ProductValidation.valid(input, MinorUnits.of(currency.current()));
        return CategoryServiceImpl.changing(() -> writing.execute(status -> {
            lists.lock(at.branch()).orElseThrow(() -> new ApplicationProblemException(409, ErrorCode.INVALID_STATE));
            if (products.countInBranch(at.branch()) >= CatalogueLimits.MAX_PRODUCTS) {
                throw new ApplicationProblemException(409, ErrorCode.LIMIT_REACHED);
            }
            refuseTakenName(at.branch(), details.name(), null);
            Instant now = clock.instant();
            SalonProduct product = products.saveAndFlush(SalonProduct.added(at.branch(), details, now));
            audit.record(
                    at.actor(), ADDED, CatalogueAudit.PRODUCT, product.getId(), null, values(product.details()), now);
            return view(product);
        }));
    }

    @Override
    public ProductView save(UUID id, ProductInput input, Long version) {
        Scope at = scope.of(CatalogueScope.CHANGE);
        Details details = ProductValidation.valid(input, MinorUnits.of(currency.current()));
        long expected = CategoryServiceImpl.requiredVersion("version", version);
        return CategoryServiceImpl.changing(() -> writing.execute(status -> {
            SalonProduct product = products.findInBranch(id, at.branch()).orElseThrow(NotFoundException::new);
            if (product.getVersion() != expected) {
                throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
            }
            Details before = product.details();
            if (before.equals(details)) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            refuseTakenName(at.branch(), details.name(), id);
            Instant now = clock.instant();
            product.change(details, now);
            products.flush();
            audit.record(at.actor(), UPDATED, CatalogueAudit.PRODUCT, id, values(before), values(details), now);
            return view(product);
        }));
    }

    @Override
    public void delete(UUID id, Long version) {
        Scope at = scope.of(CatalogueScope.CHANGE);
        long expected = CategoryServiceImpl.requiredVersion("version", version);
        CategoryServiceImpl.changing(() -> writing.execute(status -> {
            SalonProduct product = products.findInBranch(id, at.branch()).orElseThrow(NotFoundException::new);
            if (product.getVersion() != expected) {
                throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
            }
            Map<String, Object> before = values(product.details());
            Instant now = clock.instant();
            products.delete(product);
            products.flush();
            audit.record(at.actor(), DELETED, CatalogueAudit.PRODUCT, id, before, null, now);
            return null;
        }));
    }

    private void refuseTakenName(UUID branch, String name, UUID except) {
        if (products.nameTaken(branch, name, except)) {
            throw new ApplicationProblemException(
                    400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("name", "is used by another product")));
        }
    }

    private static ProductView view(SalonProduct product) {
        return new ProductView(
                product.getId(),
                product.getName(),
                product.getPriceMinor(),
                product.getCurrency(),
                product.isChargesVat(),
                product.isSoldAtCheckout(),
                product.getVersion());
    }

    private static Map<String, Object> values(Details details) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", details.name());
        values.put("priceMinor", details.priceMinor());
        values.put("currency", details.currency());
        values.put("chargesVat", details.chargesVat());
        values.put("soldAtCheckout", details.soldAtCheckout());
        return values;
    }
}
