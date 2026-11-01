package com.salonplatform.catalogue.service.impl;

import static com.salonplatform.catalogue.service.impl.CategoryServiceImpl.changing;
import static com.salonplatform.catalogue.service.impl.CategoryServiceImpl.requiredVersion;

import com.salonplatform.catalogue.domain.PopularModes;
import com.salonplatform.catalogue.entity.PopularSetting;
import com.salonplatform.catalogue.repository.CategoryRepository;
import com.salonplatform.catalogue.repository.PopularSettingRepository;
import com.salonplatform.catalogue.repository.PopularSettingRows;
import com.salonplatform.catalogue.repository.SalonServiceRepository;
import com.salonplatform.catalogue.service.PopularSettings;
import com.salonplatform.catalogue.service.impl.CatalogueScope.Scope;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The setting is read without writing: before its first switch the branch has no row, and the setting is "Chosen by
 * you" at version 0. A switch creates the row if needed, locks it and checks the version. The list is worked out
 * after the transaction, because "Most booked" asks {@code booking} in a transaction of its own.
 */
@Service
class PopularSettingsImpl implements PopularSettings {

    private static final String SWITCHED = "catalogue.popular_setting_changed";
    private static final List<String> MODES = List.of(PopularModes.CHOSEN, PopularModes.MOST_BOOKED);

    private final CatalogueScope scope;
    private final PopularSettingRepository settings;
    private final PopularSettingRows rows;
    private final CategoryRepository categories;
    private final SalonServiceRepository services;
    private final PopularLists lists;
    private final CatalogueAudit audit;
    private final Clock clock;
    private final TransactionTemplate writing;
    private final TransactionTemplate reading;

    PopularSettingsImpl(
            CatalogueScope scope,
            PopularSettingRepository settings,
            PopularSettingRows rows,
            CategoryRepository categories,
            SalonServiceRepository services,
            PopularLists lists,
            CatalogueAudit audit,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.scope = scope;
        this.settings = settings;
        this.rows = rows;
        this.categories = categories;
        this.services = services;
        this.lists = lists;
        this.audit = audit;
        this.clock = clock;
        this.writing = new TransactionTemplate(transactions);
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
    }

    /** The setting as read in one transaction, and the services the list is made from. */
    private record Read(String mode, long version, List<PopularLists.Candidate> candidates) {}

    @Override
    public PopularView setting() {
        Scope at = scope.of(CatalogueScope.READ);
        Read read = reading.execute(status -> settings.findOfBranch(at.branch())
                .map(setting -> read(setting.getMode(), setting.getVersion(), at))
                .orElseGet(() -> read(PopularModes.CHOSEN, 0, at)));
        return view(read);
    }

    @Override
    public PopularView switchTo(String mode, Long version) {
        Scope at = scope.of(CatalogueScope.CHANGE);
        if (mode == null || !MODES.contains(mode)) {
            throw new ApplicationProblemException(
                    400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("mode", "must be chosen or most_booked")));
        }
        long expected = requiredVersion("version", version);
        Read read = changing(() -> writing.execute(status -> {
            Instant now = clock.instant();
            rows.ensure(at.branch(), now);
            PopularSetting setting = settings.lock(at.branch()).orElseThrow();
            if (setting.getVersion() != expected) {
                throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
            }
            if (setting.getMode().equals(mode)) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            String before = setting.getMode();
            setting.switchTo(mode, now);
            settings.flush();
            audit.record(
                    at.actor(),
                    SWITCHED,
                    CatalogueAudit.POPULAR_SETTING,
                    setting.getId(),
                    Map.of("mode", before),
                    Map.of("mode", mode),
                    now);
            return read(setting.getMode(), setting.getVersion(), at);
        }));
        return view(read);
    }

    private Read read(String mode, long version, Scope at) {
        return new Read(
                mode,
                version,
                PopularLists.inMenuOrder(categories.inOrder(at.branch()), services.bookableOnline(at.branch())));
    }

    private PopularView view(Read read) {
        List<UUID> popular = lists.popular(read.mode(), read.candidates());
        return new PopularView(read.mode(), read.version(), popular);
    }
}
