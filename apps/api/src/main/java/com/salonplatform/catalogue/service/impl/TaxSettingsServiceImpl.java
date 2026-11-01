package com.salonplatform.catalogue.service.impl;

import com.salonplatform.catalogue.TaxSettingsVersion;
import com.salonplatform.catalogue.TaxSettingsVersion.ServiceCharge;
import com.salonplatform.catalogue.TaxSettingsVersion.Vat;
import com.salonplatform.catalogue.config.CatalogueSettings;
import com.salonplatform.catalogue.domain.TaxChanges;
import com.salonplatform.catalogue.entity.TaxVersion;
import com.salonplatform.catalogue.repository.TaxVersionRepository;
import com.salonplatform.catalogue.service.TaxSettingsService;
import com.salonplatform.catalogue.service.impl.CatalogueScope.Scope;
import com.salonplatform.identity.StaffLabels;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A save reads the branch's newest version, checks the version it was sent and that something changed, and inserts
 * the next number, as booking rules do (F04-S04). Two saves made from the same version both insert that number; the
 * unique number makes the second wait for the first and fail, which answers {@code conflict}, so exactly one is kept.
 * The authors' labels are read after the transaction ends, as their contract asks.
 */
@Service
class TaxSettingsServiceImpl implements TaxSettingsService {

    private static final String SAVED = "catalogue.tax_saved";
    private static final int MAX_PAGE = 100_000;
    private static final int MAX_SIZE = 100;

    private final CatalogueScope scope;
    private final TaxVersionRepository versions;
    private final CatalogueSettings.Tax configured;
    private final StaffLabels labels;
    private final CatalogueAudit audit;
    private final Clock clock;
    private final TransactionTemplate writing;
    private final TransactionTemplate reading;

    TaxSettingsServiceImpl(
            CatalogueScope scope,
            TaxVersionRepository versions,
            CatalogueSettings settings,
            StaffLabels labels,
            CatalogueAudit audit,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.scope = scope;
        this.versions = versions;
        this.configured = settings.tax();
        this.labels = labels;
        this.audit = audit;
        this.clock = clock;
        this.writing = new TransactionTemplate(transactions);
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
    }

    @Override
    public Optional<TaxSettingsVersion> publicTax() {
        Scope at = scope.publicly();
        return current(at);
    }

    @Override
    public Settings settings() {
        Scope at = scope.of(CatalogueScope.SETTINGS_READ);
        return settingsOf(current(at).orElse(null));
    }

    @Override
    public Settings save(Long version, VatInput vat, ServiceChargeInput serviceCharge) {
        Scope at = scope.of(CatalogueScope.SETTINGS_MANAGE);
        if (version == null || version < 0) {
            throw new ApplicationProblemException(
                    400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("version", "must be zero or more")));
        }
        TaxValidation.Valid valid = TaxValidation.valid(vat, serviceCharge, configured);
        TaxSettingsVersion saved = writing.execute(status -> {
            Optional<TaxVersion> newest = versions.findFirstByBranchIdOrderByVersionDesc(at.branch());
            long current = newest.map(TaxVersion::getVersion).orElse(0L);
            if (current != version) {
                throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
            }
            Vat beforeVat = newest.map(TaxVersion::vat).orElse(null);
            ServiceCharge beforeCharge = newest.map(TaxVersion::serviceCharge).orElse(null);
            List<String> changed = TaxChanges.between(beforeVat, beforeCharge, valid.vat(), valid.serviceCharge());
            if (changed.isEmpty()) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            Instant now = clock.instant();
            TaxVersion row = TaxVersion.saved(
                    at.branch(),
                    current + 1,
                    valid.vat(),
                    valid.serviceCharge(),
                    changed,
                    at.actor().staffId(),
                    now);
            try {
                versions.persist(row);
                versions.flush();
            } catch (DataIntegrityViolationException ex) {
                throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
            }
            Map<String, Object> after = values(row.vat(), row.serviceCharge());
            after.put("version", row.getVersion());
            after.put("changedFields", changed);
            audit.record(
                    at.actor(),
                    SAVED,
                    CatalogueAudit.TAX_VERSION,
                    row.getId(),
                    beforeVat == null ? null : values(beforeVat, beforeCharge),
                    after,
                    now);
            return row.settings();
        });
        return settingsOf(saved);
    }

    @Override
    public History history(int page, int size) {
        Scope at = scope.of(CatalogueScope.SETTINGS_READ);
        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_SIZE) {
            throw new ApplicationProblemException(
                    400,
                    ErrorCode.BAD_REQUEST,
                    List.of(new FieldProblem("page", "page must be 0 to 100000 and size 1 to 100")));
        }
        Page<TaxVersion> rows = reading.execute(status -> versions.history(at.branch(), PageRequest.of(page, size)));
        Map<UUID, String> authors = labels.labels(
                rows.stream().map(TaxVersion::getCreatedBy).distinct().toList());
        List<HistoryEntry> items = rows.stream()
                .map(row -> new HistoryEntry(
                        row.getId(),
                        row.getVersion(),
                        row.getCreatedAt(),
                        row.getCreatedBy(),
                        authors.get(row.getCreatedBy()),
                        row.changedFieldNames()))
                .toList();
        return new History(items, page, size, rows.getTotalElements());
    }

    private Optional<TaxSettingsVersion> current(Scope at) {
        return reading.execute(status ->
                versions.findFirstByBranchIdOrderByVersionDesc(at.branch()).map(TaxVersion::settings));
    }

    private Settings settingsOf(TaxSettingsVersion current) {
        Configured now = new Configured(
                configured.vat().name(),
                configured.vat().ratePercent(),
                configured.vat().registrationLabel(),
                configured.serviceCharge().name(),
                configured.serviceCharge().maxPercent());
        return new Settings(current == null ? 0 : current.version(), current, now);
    }

    /** The values the audit trail keeps of a version, the registration number included: receipts print it. */
    private static Map<String, Object> values(Vat vat, ServiceCharge charge) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("vatRegistered", vat.registered());
        values.put("vatRegistrationNumber", vat.registrationNumber());
        values.put("vatName", vat.name());
        values.put("vatRatePercent", vat.ratePercent());
        values.put("serviceChargeOn", charge.on());
        values.put("serviceChargePercent", charge.on() ? charge.percent() : null);
        values.put("serviceChargeOnProducts", charge.onProducts());
        values.put("serviceChargeName", charge.name());
        return values;
    }
}
