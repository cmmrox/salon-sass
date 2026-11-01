package com.salonplatform.catalogue.service.impl;

import com.salonplatform.catalogue.domain.CatalogueLimits;
import com.salonplatform.catalogue.entity.Category;
import com.salonplatform.catalogue.entity.CategoryList;
import com.salonplatform.catalogue.repository.CategoryListRepository;
import com.salonplatform.catalogue.repository.CategoryRepository;
import com.salonplatform.catalogue.repository.SalonServiceRepository;
import com.salonplatform.catalogue.service.CategoryService;
import com.salonplatform.catalogue.service.impl.CatalogueScope.Scope;
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
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An add, delete or move locks the branch's category list and raises its version, so two of them wait for each
 * other and a move made from a stale list is refused; a rename is guarded by the category's own version. A refusal
 * rolls the transaction back, the raised version with it. The
 * unique name index and the deferred unique position are the database's backstop: a violation answers
 * {@code conflict} and nothing is kept.
 */
@Service
class CategoryServiceImpl implements CategoryService {

    private static final String ADDED = "catalogue.category_added";
    private static final String RENAMED = "catalogue.category_renamed";
    private static final String MOVED = "catalogue.category_moved";
    private static final String DELETED = "catalogue.category_deleted";

    private final CatalogueScope scope;
    private final CategoryListRepository lists;
    private final CategoryRepository categories;
    private final SalonServiceRepository services;
    private final CatalogueAudit audit;
    private final Clock clock;
    private final TransactionTemplate writing;
    private final TransactionTemplate reading;

    CategoryServiceImpl(
            CatalogueScope scope,
            CategoryListRepository lists,
            CategoryRepository categories,
            SalonServiceRepository services,
            CatalogueAudit audit,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.scope = scope;
        this.lists = lists;
        this.categories = categories;
        this.services = services;
        this.audit = audit;
        this.clock = clock;
        this.writing = new TransactionTemplate(transactions);
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
    }

    @Override
    public OrderedCategories list() {
        Scope at = scope.of(CatalogueScope.READ);
        return reading.execute(status -> listOf(at.branch()));
    }

    @Override
    public CategoryView add(String name) {
        Scope at = scope.of(CatalogueScope.CHANGE);
        String checked = validName(name);
        return changing(() -> writing.execute(status -> {
            lists.lock(at.branch()).orElseThrow(CategoryServiceImpl::noList).changed(clock.instant());
            List<Category> existing = categories.inOrder(at.branch());
            if (existing.size() >= CatalogueLimits.MAX_CATEGORIES) {
                throw new ApplicationProblemException(409, ErrorCode.LIMIT_REACHED);
            }
            refuseTakenName(at.branch(), checked, null);
            int position = existing.isEmpty() ? 1 : existing.getLast().getPosition() + 1;
            Instant now = clock.instant();
            Category category = categories.saveAndFlush(Category.added(at.branch(), checked, position, now));
            audit.record(at.actor(), ADDED, CatalogueAudit.CATEGORY, category.getId(), null, values(category), now);
            return view(category, 0);
        }));
    }

    @Override
    public CategoryView rename(UUID id, String name, Long version) {
        Scope at = scope.of(CatalogueScope.CHANGE);
        String checked = validName(name);
        long expected = requiredVersion("version", version);
        return changing(() -> writing.execute(status -> {
            Category category = categories.findInBranch(id, at.branch()).orElseThrow(NotFoundException::new);
            if (category.getVersion() != expected) {
                throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
            }
            if (category.getName().equals(checked)) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            refuseTakenName(at.branch(), checked, id);
            Map<String, Object> before = values(category);
            Instant now = clock.instant();
            category.rename(checked, now);
            categories.flush();
            audit.record(at.actor(), RENAMED, CatalogueAudit.CATEGORY, id, before, values(category), now);
            return view(category, services.countInCategory(id));
        }));
    }

    @Override
    public OrderedCategories move(UUID id, String direction, Long listVersion) {
        Scope at = scope.of(CatalogueScope.CHANGE);
        int step = switch (direction == null ? "" : direction) {
            case "up" -> -1;
            case "down" -> 1;
            default ->
                throw new ApplicationProblemException(
                        400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("direction", "must be up or down")));
        };
        long expected = requiredVersion("listVersion", listVersion);
        return changing(() -> writing.execute(status -> {
            CategoryList list = lists.lock(at.branch()).orElseThrow(CategoryServiceImpl::noList);
            if (list.getListVersion() != expected) {
                throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
            }
            List<Category> ordered = categories.inOrder(at.branch());
            int index = indexOf(ordered, id);
            int other = index + step;
            if (other < 0 || other >= ordered.size()) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            Category moving = ordered.get(index);
            Category neighbour = ordered.get(other);
            int from = moving.getPosition();
            int to = neighbour.getPosition();
            Instant now = clock.instant();
            moving.moveTo(to, now);
            neighbour.moveTo(from, now);
            list.changed(now);
            categories.flush();
            audit.record(
                    at.actor(),
                    MOVED,
                    CatalogueAudit.CATEGORY,
                    id,
                    Map.of("position", from),
                    Map.of("position", to, "direction", direction),
                    now);
            return listOf(at.branch());
        }));
    }

    @Override
    public void delete(UUID id, Long version) {
        Scope at = scope.of(CatalogueScope.CHANGE);
        long expected = requiredVersion("version", version);
        changing(() -> writing.execute(status -> {
            lists.lock(at.branch()).orElseThrow(CategoryServiceImpl::noList).changed(clock.instant());
            Category category = categories.findInBranch(id, at.branch()).orElseThrow(NotFoundException::new);
            if (category.getVersion() != expected) {
                throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
            }
            if (services.countInCategory(id) > 0) {
                throw new ApplicationProblemException(409, ErrorCode.IN_USE);
            }
            if (categories.inOrder(at.branch()).size() <= 1) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            Map<String, Object> before = values(category);
            Instant now = clock.instant();
            categories.delete(category);
            categories.flush();
            audit.record(at.actor(), DELETED, CatalogueAudit.CATEGORY, id, before, null, now);
            return null;
        }));
    }

    private OrderedCategories listOf(UUID branch) {
        long listVersion =
                lists.findOfBranch(branch).map(list -> list.getListVersion()).orElse(0L);
        Map<UUID, Long> counts = services.countsByCategory(branch).stream()
                .collect(Collectors.toMap(
                        SalonServiceRepository.CategoryCount::categoryId,
                        SalonServiceRepository.CategoryCount::services));
        List<CategoryView> items = categories.inOrder(branch).stream()
                .map(category -> view(category, counts.getOrDefault(category.getId(), 0L)))
                .toList();
        return new OrderedCategories(listVersion, items);
    }

    private void refuseTakenName(UUID branch, String name, UUID except) {
        if (categories.nameTaken(branch, name, except)) {
            throw new ApplicationProblemException(
                    400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("name", "is used by another category")));
        }
    }

    private static int indexOf(List<Category> ordered, UUID id) {
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).getId().equals(id)) {
                return i;
            }
        }
        throw new NotFoundException();
    }

    private static String validName(String name) {
        String trimmed = name == null ? "" : name.strip();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < CatalogueLimits.CATEGORY_NAME_MIN || length > CatalogueLimits.CATEGORY_NAME_MAX) {
            throw new ApplicationProblemException(
                    400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("name", "must be 2 to 30 characters")));
        }
        return trimmed;
    }

    static long requiredVersion(String field, Long version) {
        if (version == null || version < 0) {
            throw new ApplicationProblemException(
                    400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem(field, "must be zero or more")));
        }
        return version;
    }

    /** A lost race on the name index, the order or a version: nothing is kept, and the caller reads again. */
    static <T> T changing(java.util.function.Supplier<T> change) {
        try {
            return change.get();
        } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException ex) {
            throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
        }
    }

    private static ApplicationProblemException noList() {
        // Every salon is given its list with its default categories; one without it cannot change its categories.
        return new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
    }

    private static CategoryView view(Category category, long serviceCount) {
        return new CategoryView(
                category.getId(), category.getName(), category.getPosition(), serviceCount, category.getVersion());
    }

    private static Map<String, Object> values(Category category) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", category.getName());
        values.put("position", category.getPosition());
        return values;
    }
}
