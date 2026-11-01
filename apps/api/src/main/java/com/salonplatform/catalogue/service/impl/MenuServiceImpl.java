package com.salonplatform.catalogue.service.impl;

import com.salonplatform.catalogue.ServiceVariant;
import com.salonplatform.catalogue.domain.PopularModes;
import com.salonplatform.catalogue.domain.Ranges;
import com.salonplatform.catalogue.domain.Segments;
import com.salonplatform.catalogue.entity.Category;
import com.salonplatform.catalogue.entity.PopularSetting;
import com.salonplatform.catalogue.entity.SalonService;
import com.salonplatform.catalogue.entity.TermsVersion;
import com.salonplatform.catalogue.repository.CategoryRepository;
import com.salonplatform.catalogue.repository.PopularSettingRepository;
import com.salonplatform.catalogue.repository.SalonServiceRepository;
import com.salonplatform.catalogue.service.MenuService;
import com.salonplatform.catalogue.service.impl.CatalogueScope.Scope;
import com.salonplatform.platform.SalonCurrency;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Five reads in one read-only transaction: the categories in order, the bookable services, their terms with their
 * variants, and the Popular setting. The Popular list is worked out after it, because "Most booked" asks
 * {@code booking} in a transaction of its own.
 */
@Service
class MenuServiceImpl implements MenuService {

    private final CatalogueScope scope;
    private final CategoryRepository categories;
    private final SalonServiceRepository services;
    private final TermsRows terms;
    private final PopularSettingRepository settings;
    private final PopularLists popular;
    private final SalonCurrency currency;
    private final TransactionTemplate reading;

    MenuServiceImpl(
            CatalogueScope scope,
            CategoryRepository categories,
            SalonServiceRepository services,
            TermsRows terms,
            PopularSettingRepository settings,
            PopularLists popular,
            SalonCurrency currency,
            PlatformTransactionManager transactions) {
        this.scope = scope;
        this.categories = categories;
        this.services = services;
        this.terms = terms;
        this.settings = settings;
        this.popular = popular;
        this.currency = currency;
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
    }

    /** The menu without its Popular list, the setting's mode and the services the list is made from. */
    private record Read(List<MenuCategory> categories, String mode, List<PopularLists.Candidate> candidates) {}

    @Override
    public Menu menu() {
        Scope at = scope.publicly();
        String salonCurrency = currency.current();
        Read read = reading.execute(status -> {
            List<SalonService> bookable = services.bookableOnline(at.branch());
            Map<UUID, TermsRows.Read> held = terms.all(
                    bookable.stream().map(SalonService::getCurrentTermsId).toList());
            Map<UUID, List<MenuItem>> byCategory = new LinkedHashMap<>();
            for (SalonService service : bookable) {
                byCategory
                        .computeIfAbsent(service.getCategoryId(), key -> new ArrayList<>())
                        .add(item(service, held.get(service.getCurrentTermsId())));
            }
            List<Category> ordered = categories.inOrder(at.branch());
            List<MenuCategory> shown = ordered.stream()
                    .filter(category -> byCategory.containsKey(category.getId()))
                    .map(category -> new MenuCategory(
                            category.getId(), category.getName(), List.copyOf(byCategory.get(category.getId()))))
                    .toList();
            String mode = settings.findOfBranch(at.branch())
                    .map(PopularSetting::getMode)
                    .orElse(PopularModes.CHOSEN);
            return new Read(shown, mode, PopularLists.inMenuOrder(ordered, bookable));
        });
        return new Menu(salonCurrency, read.categories(), popular.popular(read.mode(), read.candidates()));
    }

    private static MenuItem item(SalonService service, TermsRows.Read read) {
        TermsVersion.Terms held = read.terms();
        Ranges ranges = Ranges.of(held.priceMinor(), held.segments(), held.variants());
        return new MenuItem(
                service.getId(),
                service.getName(),
                service.getDescription(),
                held.priceMinor(),
                held.currency(),
                Segments.duration(held.segments()),
                service.getPhotoId(),
                read.row().getId(),
                held.variantSetName(),
                held.variants().stream().map(MenuServiceImpl::variant).toList(),
                ranges.priceMinMinor(),
                ranges.priceMaxMinor(),
                ranges.durationMinMinutes(),
                ranges.durationMaxMinutes(),
                held.deposit());
    }

    private static MenuVariant variant(ServiceVariant variant) {
        return new MenuVariant(
                variant.variantId(), variant.name(), variant.priceMinor(), Segments.duration(variant.segments()));
    }
}
