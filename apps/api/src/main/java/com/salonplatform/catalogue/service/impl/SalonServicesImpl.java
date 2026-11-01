package com.salonplatform.catalogue.service.impl;

import static com.salonplatform.catalogue.service.impl.CategoryServiceImpl.changing;
import static com.salonplatform.catalogue.service.impl.CategoryServiceImpl.requiredVersion;

import com.salonplatform.catalogue.ServiceDeposit;
import com.salonplatform.catalogue.ServiceVariant;
import com.salonplatform.catalogue.UpcomingServiceBookings;
import com.salonplatform.catalogue.domain.CatalogueLimits;
import com.salonplatform.catalogue.domain.CopyNames;
import com.salonplatform.catalogue.domain.MinorUnits;
import com.salonplatform.catalogue.domain.Segments;
import com.salonplatform.catalogue.entity.SalonService;
import com.salonplatform.catalogue.entity.ServicePhoto;
import com.salonplatform.catalogue.entity.TermsVersion;
import com.salonplatform.catalogue.repository.CategoryListRepository;
import com.salonplatform.catalogue.repository.CategoryRepository;
import com.salonplatform.catalogue.repository.PopularSettingRepository;
import com.salonplatform.catalogue.repository.PopularSettingRows;
import com.salonplatform.catalogue.repository.SalonServiceRepository;
import com.salonplatform.catalogue.repository.ServicePhotoRepository;
import com.salonplatform.catalogue.service.SalonServices;
import com.salonplatform.catalogue.service.impl.CatalogueScope.Scope;
import com.salonplatform.catalogue.service.impl.ServiceValidation.Valid;
import com.salonplatform.catalogue.service.impl.ServiceValidation.ValidVariant;
import com.salonplatform.platform.SalonCurrency;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.shared.web.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A service and its first terms version (with its variants) are written in one transaction; a save of price, time,
 * buffer, variants or deposit inserts the next version and points the service at it. A variant keeps its id from one
 * version to the next; a new one gets a new id. A choice for Popular locks the branch's Popular setting first, so two
 * choices of a sixth service wait for each other and exactly one is kept. Two saves made from the same version both pass the version check, and
 * the second fails on the service's own version (or the version number), answering {@code conflict}: exactly one
 * is kept. A creation or duplicate locks the branch's category list, so the service limit holds. The salon's
 * currency and the booking module's answer are read before the transaction opens, as their contracts ask.
 */
@Service
class SalonServicesImpl implements SalonServices {

    private static final String CREATED = "catalogue.service_created";
    private static final String UPDATED = "catalogue.service_updated";
    private static final String DUPLICATED = "catalogue.service_duplicated";
    private static final String ONLINE = "catalogue.service_online_changed";
    private static final String DELETED = "catalogue.service_deleted";
    private static final String POPULAR = "catalogue.service_popular_changed";

    private final CatalogueScope scope;
    private final CategoryListRepository lists;
    private final CategoryRepository categories;
    private final SalonServiceRepository services;
    private final TermsRows terms;
    private final ServicePhotoRepository photos;
    private final PopularSettingRows popularRows;
    private final PopularSettingRepository popularSettings;
    private final ServiceViews views;
    private final UpcomingServiceBookings upcoming;
    private final SalonCurrency currency;
    private final CatalogueAudit audit;
    private final Clock clock;
    private final TransactionTemplate writing;
    private final TransactionTemplate reading;

    SalonServicesImpl(
            CatalogueScope scope,
            CategoryListRepository lists,
            CategoryRepository categories,
            SalonServiceRepository services,
            TermsRows terms,
            ServicePhotoRepository photos,
            PopularSettingRows popularRows,
            PopularSettingRepository popularSettings,
            ServiceViews views,
            UpcomingServiceBookings upcoming,
            SalonCurrency currency,
            CatalogueAudit audit,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.scope = scope;
        this.lists = lists;
        this.categories = categories;
        this.services = services;
        this.terms = terms;
        this.photos = photos;
        this.popularRows = popularRows;
        this.popularSettings = popularSettings;
        this.views = views;
        this.upcoming = upcoming;
        this.currency = currency;
        this.audit = audit;
        this.clock = clock;
        this.writing = new TransactionTemplate(transactions);
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
    }

    @Override
    public List<ServiceView> list() {
        Scope at = scope.of(CatalogueScope.READ);
        return reading.execute(status -> views.of(services.live(at.branch())));
    }

    @Override
    public ServiceView get(UUID id) {
        Scope at = scope.of(CatalogueScope.READ);
        return reading.execute(status -> views.of(live(id, at)));
    }

    @Override
    public ServiceView create(ServiceInput input) {
        Scope at = scope.of(CatalogueScope.CHANGE);
        String salonCurrency = currency.current();
        Valid valid = ServiceValidation.valid(input, MinorUnits.of(salonCurrency), false);
        return changing(() -> writing.execute(status -> {
            lockForNewService(at);
            refuseForeignCategory(valid.categoryId(), at);
            refuseTakenName(valid.name(), null, at);
            Instant now = clock.instant();
            UUID id = UUID.randomUUID();
            TermsVersion.Terms firstTerms = termsOf(valid, salonCurrency, List.of());
            TermsVersion first = TermsVersion.made(at.branch(), id, 1, firstTerms, staff(at), now);
            SalonService service = SalonService.created(id, at.branch(), detailsOf(valid), first.getId(), null, now);
            services.save(service);
            TermsRows.Read written = terms.write(first, firstTerms);
            services.flush();
            audit.record(at.actor(), CREATED, CatalogueAudit.SERVICE, id, null, values(service, written), now);
            return ServiceViews.of(service, written);
        }));
    }

    @Override
    public ServiceView update(UUID id, ServiceInput input, Long version) {
        Scope at = scope.of(CatalogueScope.CHANGE);
        String salonCurrency = currency.current();
        Valid valid = ServiceValidation.valid(input, MinorUnits.of(salonCurrency), true);
        long expected = requiredVersion("version", version);
        return changing(() -> writing.execute(status -> {
            SalonService service = live(id, at);
            if (service.getVersion() != expected) {
                throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
            }
            refuseForeignCategory(valid.categoryId(), at);
            refuseTakenName(valid.name(), id, at);
            TermsRows.Read current = terms.require(service.getCurrentTermsId());
            TermsVersion.Terms saved =
                    termsOf(valid, salonCurrency, current.terms().variants());
            SalonService.Details details = detailsOf(valid);
            boolean newTerms = !current.terms().equals(saved);
            if (!newTerms && service.details().equals(details)) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            Map<String, Object> before = values(service, current);
            Instant now = clock.instant();
            service.describe(details, now);
            TermsRows.Read kept = current;
            if (newTerms) {
                TermsVersion next =
                        TermsVersion.made(at.branch(), id, current.row().getNumber() + 1, saved, staff(at), now);
                kept = terms.write(next, saved);
                service.retermed(next.getId(), now);
            }
            services.flush();
            audit.record(at.actor(), UPDATED, CatalogueAudit.SERVICE, id, before, values(service, kept), now);
            return ServiceViews.of(service, kept);
        }));
    }

    @Override
    public ServiceView duplicate(UUID id) {
        Scope at = scope.of(CatalogueScope.CHANGE);
        return changing(() -> writing.execute(status -> {
            lockForNewService(at);
            SalonService source = live(id, at);
            TermsRows.Read sourceTerms = terms.require(source.getCurrentTermsId());
            Set<String> taken = new HashSet<>(services.liveNames(at.branch()));
            String name = CopyNames.of(source.getName(), taken);
            Instant now = clock.instant();
            UUID photoId = null;
            if (source.getPhotoId() != null) {
                // The copy has a photo of its own, so removing either one's photo leaves the other's.
                ServicePhoto original = photos.findPhoto(source.getPhotoId()).orElseThrow();
                ServicePhoto copy = photos.persist(ServicePhoto.uploaded(
                        at.branch(),
                        original.getMediaType(),
                        original.getWidth(),
                        original.getHeight(),
                        original.getBytes(),
                        now));
                photoId = copy.getId();
            }
            UUID copyId = UUID.randomUUID();
            TermsVersion.Terms copied = withNewVariantIds(sourceTerms.terms());
            TermsVersion first = TermsVersion.made(at.branch(), copyId, 1, copied, staff(at), now);
            SalonService copy = SalonService.created(
                    copyId,
                    at.branch(),
                    new SalonService.Details(name, source.getCategoryId(), source.getDescription(), false),
                    first.getId(),
                    photoId,
                    now);
            services.save(copy);
            TermsRows.Read written = terms.write(first, copied);
            services.flush();
            Map<String, Object> after = values(copy, written);
            after.put("duplicatedFrom", id.toString());
            audit.record(at.actor(), DUPLICATED, CatalogueAudit.SERVICE, copyId, null, after, now);
            return ServiceViews.of(copy, written);
        }));
    }

    @Override
    public ServiceView choosePopular(UUID id, Boolean popular, Long version) {
        Scope at = scope.of(CatalogueScope.CHANGE);
        if (popular == null) {
            throw new ApplicationProblemException(
                    400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("popular", "is required")));
        }
        long expected = requiredVersion("version", version);
        return changing(() -> writing.execute(status -> {
            Instant now = clock.instant();
            popularRows.ensure(at.branch(), now);
            popularSettings.lock(at.branch()).orElseThrow();
            SalonService service = live(id, at);
            if (service.getVersion() != expected) {
                throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
            }
            if (service.isPopular() == popular) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            if (popular && services.countPopular(at.branch()) >= CatalogueLimits.MAX_POPULAR) {
                throw new ApplicationProblemException(409, ErrorCode.LIMIT_REACHED);
            }
            service.choosePopular(popular, now);
            services.flush();
            audit.record(
                    at.actor(),
                    POPULAR,
                    CatalogueAudit.SERVICE,
                    id,
                    Map.of("popular", !popular),
                    Map.of("popular", popular),
                    now);
            return views.of(service);
        }));
    }

    @Override
    public ServiceView showOnline(UUID id, Boolean bookableOnline, Long version) {
        Scope at = scope.of(CatalogueScope.CHANGE);
        if (bookableOnline == null) {
            throw new ApplicationProblemException(
                    400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("bookableOnline", "is required")));
        }
        long expected = requiredVersion("version", version);
        return changing(() -> writing.execute(status -> {
            SalonService service = live(id, at);
            if (service.getVersion() != expected) {
                throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
            }
            if (service.isBookableOnline() == bookableOnline) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            Instant now = clock.instant();
            service.showOnline(bookableOnline, now);
            services.flush();
            audit.record(
                    at.actor(),
                    ONLINE,
                    CatalogueAudit.SERVICE,
                    id,
                    Map.of("bookableOnline", !bookableOnline),
                    Map.of("bookableOnline", bookableOnline),
                    now);
            return views.of(service);
        }));
    }

    @Override
    public void delete(UUID id, Long version) {
        Scope at = scope.of(CatalogueScope.CHANGE);
        long expected = requiredVersion("version", version);
        // The booking module's answer, in its own transaction, before this one opens (decision 093).
        if (upcoming.anyFor(id)) {
            throw new ApplicationProblemException(409, ErrorCode.IN_USE);
        }
        changing(() -> writing.execute(status -> {
            SalonService service = live(id, at);
            if (service.getVersion() != expected) {
                throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
            }
            Map<String, Object> before = values(service, terms.require(service.getCurrentTermsId()));
            UUID photoId = service.getPhotoId();
            Instant now = clock.instant();
            service.delete(now);
            services.flush();
            if (photoId != null) {
                photos.remove(photoId);
            }
            audit.record(at.actor(), DELETED, CatalogueAudit.SERVICE, id, before, null, now);
            return null;
        }));
    }

    private SalonService live(UUID id, Scope at) {
        return services.findLive(id, at.branch()).orElseThrow(NotFoundException::new);
    }

    /** Locks the branch's list until the transaction ends, then refuses a service beyond the limit. */
    private void lockForNewService(Scope at) {
        lists.lock(at.branch()).orElseThrow(() -> new ApplicationProblemException(409, ErrorCode.INVALID_STATE));
        if (services.countLive(at.branch()) >= CatalogueLimits.MAX_SERVICES) {
            throw new ApplicationProblemException(409, ErrorCode.LIMIT_REACHED);
        }
    }

    /** Another salon's category, or an id that names none, is a field error: the two cannot be told apart. */
    private void refuseForeignCategory(UUID categoryId, Scope at) {
        if (categories.findInBranch(categoryId, at.branch()).isEmpty()) {
            throw new ApplicationProblemException(
                    400,
                    ErrorCode.BAD_REQUEST,
                    List.of(new FieldProblem("categoryId", "must be one of the salon's categories")));
        }
    }

    private void refuseTakenName(String name, UUID except, Scope at) {
        if (services.nameTaken(at.branch(), name, except)) {
            throw new ApplicationProblemException(
                    400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("name", "is used by another service")));
        }
    }

    private static UUID staff(Scope at) {
        return at.actor().staffId();
    }

    /**
     * The terms a request asks for. A variant sent with an id keeps it, and must be one of the current version's
     * ({@code current}, empty for a new service); one sent without an id is new and gets one. With variants, the
     * version's own price and time are the lowest price and the shortest time (decision 095).
     */
    private static TermsVersion.Terms termsOf(Valid valid, String currency, List<ServiceVariant> current) {
        int buffer = valid.bufferMinutes();
        if (valid.variants() == null) {
            return new TermsVersion.Terms(
                    valid.priceMinor(),
                    currency,
                    Segments.of(valid.durationMinutes(), buffer),
                    null,
                    List.of(),
                    valid.deposit());
        }
        Set<UUID> known = new HashSet<>();
        current.forEach(variant -> known.add(variant.variantId()));
        List<FieldProblem> unknown = new ArrayList<>();
        List<ServiceVariant> variants = new ArrayList<>();
        for (ValidVariant item : valid.variants().items()) {
            if (item.id() != null && !known.contains(item.id())) {
                unknown.add(new FieldProblem(
                        ServiceValidation.variantField(item.position(), "id"), "is not a variant of this service"));
            }
            UUID variantId = item.id() == null ? UUID.randomUUID() : item.id();
            variants.add(new ServiceVariant(
                    variantId, item.name(), item.priceMinor(), Segments.of(item.durationMinutes(), buffer)));
        }
        if (!unknown.isEmpty()) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, unknown);
        }
        long lowest =
                variants.stream().mapToLong(ServiceVariant::priceMinor).min().orElseThrow();
        int shortest = valid.variants().items().stream()
                .mapToInt(ValidVariant::durationMinutes)
                .min()
                .orElseThrow();
        return new TermsVersion.Terms(
                lowest,
                currency,
                Segments.of(shortest, buffer),
                valid.variants().name(),
                variants,
                valid.deposit());
    }

    /** A copy's terms: the same, its variants under new ids of their own (F05-S02 AC-9). */
    private static TermsVersion.Terms withNewVariantIds(TermsVersion.Terms source) {
        return new TermsVersion.Terms(
                source.priceMinor(),
                source.currency(),
                source.segments(),
                source.variantSetName(),
                source.variants().stream()
                        .map(variant -> new ServiceVariant(
                                UUID.randomUUID(), variant.name(), variant.priceMinor(), variant.segments()))
                        .toList(),
                source.deposit());
    }

    private static SalonService.Details detailsOf(Valid valid) {
        return new SalonService.Details(valid.name(), valid.categoryId(), valid.description(), valid.bookableOnline());
    }

    /** What the audit records of a service: its fields and current terms, never a photo's bytes. */
    private static Map<String, Object> values(SalonService service, TermsRows.Read read) {
        TermsVersion terms = read.row();
        TermsVersion.Terms held = read.terms();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", service.getName());
        values.put(
                "categoryId",
                service.getCategoryId() == null ? null : service.getCategoryId().toString());
        values.put("description", service.getDescription());
        values.put("bookableOnline", service.isBookableOnline());
        values.put(
                "photoId",
                service.getPhotoId() == null ? null : service.getPhotoId().toString());
        values.put("priceMinor", terms.getPriceMinor());
        values.put("currency", terms.getCurrency());
        values.put("durationMinutes", Segments.duration(terms.segments()));
        values.put("bufferMinutes", Segments.buffer(terms.segments()));
        values.put("popular", service.isPopular());
        values.put("variantSetName", held.variantSetName());
        values.put(
                "variants",
                held.variants().stream()
                        .map(variant -> {
                            Map<String, Object> each = new LinkedHashMap<>();
                            each.put("id", variant.variantId().toString());
                            each.put("name", variant.name());
                            each.put("priceMinor", variant.priceMinor());
                            each.put("durationMinutes", Segments.duration(variant.segments()));
                            return each;
                        })
                        .toList());
        values.put("deposit", depositValues(held.deposit()));
        values.put("termsVersionId", terms.getId().toString());
        values.put("termsVersion", terms.getNumber());
        return values;
    }

    private static Map<String, Object> depositValues(ServiceDeposit deposit) {
        Map<String, Object> values = new LinkedHashMap<>();
        switch (deposit) {
            case ServiceDeposit.SalonRule _ -> values.put("kind", "salon_rule");
            case ServiceDeposit.None _ -> values.put("kind", "none");
            case ServiceDeposit.Percentage percentage -> {
                values.put("kind", "percentage");
                values.put("percent", percentage.percent());
            }
            case ServiceDeposit.Amount amount -> {
                values.put("kind", "amount");
                values.put("amountMinor", amount.amountMinor());
            }
        }
        return values;
    }
}
