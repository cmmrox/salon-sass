package com.salonplatform.booking.service.impl;

import com.salonplatform.booking.BookingPolicies;
import com.salonplatform.booking.BookingRules;
import com.salonplatform.booking.DepositRule;
import com.salonplatform.booking.entity.BookingRulesVersion;
import com.salonplatform.booking.repository.BookingRulesVersionRepository;
import com.salonplatform.booking.service.impl.BookingScope.Scope;
import com.salonplatform.shared.persistence.ReadOnlyWork;
import com.salonplatform.shared.tenancy.TenantContext;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Reads versions in a read-only tenant transaction of its own; another salon's version is not seen, because
 * the tenant filter and row-level security both hide it.
 */
@Service
class BookingPoliciesImpl implements BookingPolicies {

    private final BookingScope scope;
    private final BookingRulesVersionRepository versions;
    private final ReadOnlyWork reading;

    BookingPoliciesImpl(BookingScope scope, BookingRulesVersionRepository versions, ReadOnlyWork reading) {
        this.scope = scope;
        this.versions = versions;
        this.reading = reading;
    }

    @Override
    public Optional<BookingRules> current() {
        Scope at = scope.publicly();
        return reading.read(() ->
                versions.findFirstByBranchIdOrderByVersionDesc(at.branch()).map(BookingRulesVersion::rules));
    }

    @Override
    public Optional<BookingRules> version(UUID versionId) {
        TenantContext.require();
        if (versionId == null) {
            return Optional.empty();
        }
        return reading.read(() -> versions.findVersion(versionId).map(BookingRulesVersion::rules));
    }

    @Override
    public DepositRule depositFor(BookingRules rules, Optional<DepositRule> serviceRule) {
        Objects.requireNonNull(rules, "rules");
        return serviceRule.orElse(rules.terms().deposit());
    }
}
