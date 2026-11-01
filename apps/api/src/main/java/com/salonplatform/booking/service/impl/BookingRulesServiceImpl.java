package com.salonplatform.booking.service.impl;

import com.salonplatform.booking.BookingRules;
import com.salonplatform.booking.BookingTerms;
import com.salonplatform.booking.domain.MinorUnits;
import com.salonplatform.booking.domain.RuleChanges;
import com.salonplatform.booking.entity.BookingRulesVersion;
import com.salonplatform.booking.repository.BookingRulesVersionRepository;
import com.salonplatform.booking.service.BookingRulesService;
import com.salonplatform.booking.service.impl.BookingScope.Scope;
import com.salonplatform.identity.StaffLabels;
import com.salonplatform.platform.SalonCurrency;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import java.time.Clock;
import java.time.Instant;
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
 * A save reads the branch's newest version, checks the version it was sent and that something changed, and
 * inserts the next number. Two saves made from the same version both insert that number; the unique number
 * makes the second wait for the first and fail, which answers {@code conflict}, so exactly one is kept. The
 * salon's currency and the authors' labels are read before the transaction opens or after it ends, as their
 * contracts ask.
 */
@Service
class BookingRulesServiceImpl implements BookingRulesService {

    private static final int MAX_PAGE = 100_000;
    private static final int MAX_SIZE = 100;

    private final BookingScope scope;
    private final BookingRulesVersionRepository versions;
    private final SalonCurrency currency;
    private final StaffLabels labels;
    private final BookingAudit audit;
    private final Clock clock;
    private final TransactionTemplate writing;
    private final TransactionTemplate reading;

    BookingRulesServiceImpl(
            BookingScope scope,
            BookingRulesVersionRepository versions,
            SalonCurrency currency,
            StaffLabels labels,
            BookingAudit audit,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.scope = scope;
        this.versions = versions;
        this.currency = currency;
        this.labels = labels;
        this.audit = audit;
        this.clock = clock;
        this.writing = new TransactionTemplate(transactions);
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
    }

    @Override
    public Optional<BookingRules> publicRules() {
        Scope at = scope.publicly();
        return current(at);
    }

    @Override
    public RulesSettings settings() {
        Scope at = scope.of(BookingScope.READ);
        return settingsOf(current(at));
    }

    @Override
    public RulesSettings save(Long version, RulesInput input) {
        Scope at = scope.of(BookingScope.MANAGE);
        if (version == null || version < 0) {
            throw new ApplicationProblemException(
                    400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("version", "must be zero or more")));
        }
        String salonCurrency = currency.current();
        BookingTerms terms = BookingRulesValidation.valid(input, MinorUnits.of(salonCurrency));
        BookingRules saved = writing.execute(status -> {
            Optional<BookingRulesVersion> newest = versions.findFirstByBranchIdOrderByVersionDesc(at.branch());
            long current = newest.map(BookingRulesVersion::getVersion).orElse(0L);
            if (current != version) {
                throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
            }
            BookingTerms before = newest.map(BookingRulesVersion::terms).orElse(null);
            if (newest.isPresent()
                    && before.equals(terms)
                    && newest.get().getCurrency().equals(salonCurrency)) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            List<String> changed = RuleChanges.between(before, terms);
            Instant now = clock.instant();
            BookingRulesVersion row = BookingRulesVersion.saved(
                    at.branch(),
                    current + 1,
                    terms,
                    salonCurrency,
                    changed,
                    at.actor().staffId(),
                    now);
            try {
                versions.persist(row);
                versions.flush();
            } catch (DataIntegrityViolationException ex) {
                throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
            }
            audit.saved(at.actor(), row.getId(), row.getVersion(), changed, now);
            return row.rules();
        });
        return new RulesSettings(saved.version(), saved, saved.currency());
    }

    @Override
    public History history(int page, int size) {
        Scope at = scope.of(BookingScope.READ);
        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_SIZE) {
            throw new ApplicationProblemException(
                    400,
                    ErrorCode.BAD_REQUEST,
                    List.of(new FieldProblem("page", "page must be 0 to 100000 and size 1 to 100")));
        }
        Page<BookingRulesVersion> rows =
                reading.execute(status -> versions.history(at.branch(), PageRequest.of(page, size)));
        Map<UUID, String> authors = labels.labels(
                rows.stream().map(BookingRulesVersion::getCreatedBy).distinct().toList());
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

    private Optional<BookingRules> current(Scope at) {
        return reading.execute(status ->
                versions.findFirstByBranchIdOrderByVersionDesc(at.branch()).map(BookingRulesVersion::rules));
    }

    private RulesSettings settingsOf(Optional<BookingRules> rules) {
        return rules.map(saved -> new RulesSettings(saved.version(), saved, saved.currency()))
                .orElseGet(() -> new RulesSettings(0, null, currency.current()));
    }
}
