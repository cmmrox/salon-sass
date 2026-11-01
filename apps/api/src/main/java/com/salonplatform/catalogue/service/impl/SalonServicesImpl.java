package com.salonplatform.catalogue.service.impl;

import static com.salonplatform.catalogue.service.impl.CategoryServiceImpl.changing;
import static com.salonplatform.catalogue.service.impl.CategoryServiceImpl.requiredVersion;

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
import com.salonplatform.catalogue.repository.SalonServiceRepository;
import com.salonplatform.catalogue.repository.ServicePhotoRepository;
import com.salonplatform.catalogue.repository.TermsVersionRepository;
import com.salonplatform.catalogue.service.SalonServices;
import com.salonplatform.catalogue.service.impl.CatalogueScope.Scope;
import com.salonplatform.catalogue.service.impl.ServiceValidation.Valid;
import com.salonplatform.platform.SalonCurrency;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.shared.web.NotFoundException;
import java.time.Clock;
import java.time.Instant;
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
 * A service and its first terms version are written in one transaction; a save of price, time or buffer inserts the
 * next version and points the service at it. Two saves made from the same version both pass the version check, and
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

    private final CatalogueScope scope;
    private final CategoryListRepository lists;
    private final CategoryRepository categories;
    private final SalonServiceRepository services;
    private final TermsVersionRepository versions;
    private final ServicePhotoRepository photos;
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
            TermsVersionRepository versions,
            ServicePhotoRepository photos,
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
        this.versions = versions;
        this.photos = photos;
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
            TermsVersion first = TermsVersion.made(at.branch(), id, 1, termsOf(valid, salonCurrency), staff(at), now);
            SalonService service = SalonService.created(id, at.branch(), detailsOf(valid), first.getId(), null, now);
            services.save(service);
            versions.persist(first);
            services.flush();
            audit.record(at.actor(), CREATED, CatalogueAudit.SERVICE, id, null, values(service, first), now);
            return ServiceViews.of(service, first);
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
            TermsVersion current =
                    versions.findVersion(service.getCurrentTermsId()).orElseThrow();
            TermsVersion.Terms terms = termsOf(valid, salonCurrency);
            SalonService.Details details = detailsOf(valid);
            boolean newTerms = !current.terms().equals(terms);
            if (!newTerms && service.details().equals(details)) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            Map<String, Object> before = values(service, current);
            Instant now = clock.instant();
            service.describe(details, now);
            TermsVersion kept = current;
            if (newTerms) {
                kept = TermsVersion.made(at.branch(), id, current.getNumber() + 1, terms, staff(at), now);
                versions.persist(kept);
                service.retermed(kept.getId(), now);
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
            TermsVersion sourceTerms =
                    versions.findVersion(source.getCurrentTermsId()).orElseThrow();
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
            TermsVersion first = TermsVersion.made(at.branch(), copyId, 1, sourceTerms.terms(), staff(at), now);
            SalonService copy = SalonService.created(
                    copyId,
                    at.branch(),
                    new SalonService.Details(name, source.getCategoryId(), source.getDescription(), false),
                    first.getId(),
                    photoId,
                    now);
            services.save(copy);
            versions.persist(first);
            services.flush();
            Map<String, Object> after = values(copy, first);
            after.put("duplicatedFrom", id.toString());
            audit.record(at.actor(), DUPLICATED, CatalogueAudit.SERVICE, copyId, null, after, now);
            return ServiceViews.of(copy, first);
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
            TermsVersion current =
                    versions.findVersion(service.getCurrentTermsId()).orElseThrow();
            Map<String, Object> before = values(service, current);
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

    private static TermsVersion.Terms termsOf(Valid valid, String currency) {
        return new TermsVersion.Terms(
                valid.priceMinor(), currency, Segments.of(valid.durationMinutes(), valid.bufferMinutes()));
    }

    private static SalonService.Details detailsOf(Valid valid) {
        return new SalonService.Details(valid.name(), valid.categoryId(), valid.description(), valid.bookableOnline());
    }

    /** What the audit records of a service: its fields and current terms, never a photo's bytes. */
    private static Map<String, Object> values(SalonService service, TermsVersion terms) {
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
        values.put("termsVersionId", terms.getId().toString());
        values.put("termsVersion", terms.getNumber());
        return values;
    }
}
