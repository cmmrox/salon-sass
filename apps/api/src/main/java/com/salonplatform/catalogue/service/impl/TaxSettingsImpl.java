package com.salonplatform.catalogue.service.impl;

import com.salonplatform.catalogue.PriceParts;
import com.salonplatform.catalogue.TaxSettings;
import com.salonplatform.catalogue.TaxSettingsVersion;
import com.salonplatform.catalogue.domain.TaxParts;
import com.salonplatform.catalogue.entity.TaxVersion;
import com.salonplatform.catalogue.repository.TaxVersionRepository;
import com.salonplatform.catalogue.service.impl.CatalogueScope.Scope;
import com.salonplatform.shared.tenancy.TenantContext;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads versions in a read-only tenant transaction of its own; another salon's version is not seen, because the
 * tenant filter and row-level security both hide it. The breakdowns are {@link TaxParts} over the rates that apply.
 */
@Service
class TaxSettingsImpl implements TaxSettings {

    private final CatalogueScope scope;
    private final TaxVersionRepository versions;
    private final TransactionTemplate reading;

    TaxSettingsImpl(CatalogueScope scope, TaxVersionRepository versions, PlatformTransactionManager transactions) {
        this.scope = scope;
        this.versions = versions;
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
        this.reading.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Optional<TaxSettingsVersion> current() {
        Scope at = scope.publicly();
        return reading.execute(status ->
                versions.findFirstByBranchIdOrderByVersionDesc(at.branch()).map(TaxVersion::settings));
    }

    @Override
    public Optional<TaxSettingsVersion> version(UUID versionId) {
        TenantContext.require();
        if (versionId == null) {
            return Optional.empty();
        }
        return reading.execute(status -> versions.findVersion(versionId).map(TaxVersion::settings));
    }

    @Override
    public PriceParts servicePrice(TaxSettingsVersion version, long priceMinor) {
        Objects.requireNonNull(version, "version");
        return TaxParts.of(
                priceMinor, vatPercent(version, true), version.serviceCharge().percent());
    }

    @Override
    public PriceParts productPrice(TaxSettingsVersion version, long priceMinor, boolean chargesVat) {
        Objects.requireNonNull(version, "version");
        int serviceCharge =
                version.serviceCharge().onProducts() ? version.serviceCharge().percent() : 0;
        return TaxParts.of(priceMinor, vatPercent(version, chargesVat), serviceCharge);
    }

    private static int vatPercent(TaxSettingsVersion version, boolean applies) {
        return applies && version.vat().registered() ? version.vat().ratePercent() : 0;
    }
}
