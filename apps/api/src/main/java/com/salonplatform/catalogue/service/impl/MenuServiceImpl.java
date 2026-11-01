package com.salonplatform.catalogue.service.impl;

import com.salonplatform.catalogue.domain.Segments;
import com.salonplatform.catalogue.entity.SalonService;
import com.salonplatform.catalogue.entity.TermsVersion;
import com.salonplatform.catalogue.repository.CategoryRepository;
import com.salonplatform.catalogue.repository.SalonServiceRepository;
import com.salonplatform.catalogue.repository.TermsVersionRepository;
import com.salonplatform.catalogue.service.MenuService;
import com.salonplatform.catalogue.service.impl.CatalogueScope.Scope;
import com.salonplatform.platform.SalonCurrency;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Three reads in one read-only transaction: the categories in order, the bookable services, and their terms. */
@Service
class MenuServiceImpl implements MenuService {

    private final CatalogueScope scope;
    private final CategoryRepository categories;
    private final SalonServiceRepository services;
    private final TermsVersionRepository versions;
    private final SalonCurrency currency;
    private final TransactionTemplate reading;

    MenuServiceImpl(
            CatalogueScope scope,
            CategoryRepository categories,
            SalonServiceRepository services,
            TermsVersionRepository versions,
            SalonCurrency currency,
            PlatformTransactionManager transactions) {
        this.scope = scope;
        this.categories = categories;
        this.services = services;
        this.versions = versions;
        this.currency = currency;
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
    }

    @Override
    public Menu menu() {
        Scope at = scope.publicly();
        String salonCurrency = currency.current();
        return reading.execute(status -> {
            List<SalonService> bookable = services.bookableOnline(at.branch());
            Map<UUID, TermsVersion> terms = bookable.isEmpty()
                    ? Map.of()
                    : versions
                            .findVersions(bookable.stream()
                                    .map(SalonService::getCurrentTermsId)
                                    .toList())
                            .stream()
                            .collect(Collectors.toMap(TermsVersion::getId, Function.identity()));
            Map<UUID, List<MenuItem>> byCategory = new LinkedHashMap<>();
            for (SalonService service : bookable) {
                byCategory
                        .computeIfAbsent(service.getCategoryId(), key -> new ArrayList<>())
                        .add(item(service, terms.get(service.getCurrentTermsId())));
            }
            List<MenuCategory> shown = categories.inOrder(at.branch()).stream()
                    .filter(category -> byCategory.containsKey(category.getId()))
                    .map(category -> new MenuCategory(
                            category.getId(), category.getName(), List.copyOf(byCategory.get(category.getId()))))
                    .toList();
            return new Menu(salonCurrency, shown);
        });
    }

    private static MenuItem item(SalonService service, TermsVersion terms) {
        return new MenuItem(
                service.getId(),
                service.getName(),
                service.getDescription(),
                terms.getPriceMinor(),
                terms.getCurrency(),
                Segments.duration(terms.segments()),
                service.getPhotoId(),
                terms.getId());
    }
}
