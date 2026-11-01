package com.salonplatform.catalogue.service.impl;

import com.salonplatform.catalogue.ServiceTerms;
import com.salonplatform.catalogue.ServiceTermsVersion;
import com.salonplatform.catalogue.domain.Ranges;
import com.salonplatform.catalogue.entity.SalonService;
import com.salonplatform.catalogue.entity.TermsVersion;
import com.salonplatform.catalogue.repository.SalonServiceRepository;
import com.salonplatform.shared.tenancy.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads in a read-only tenant transaction of its own; another salon's service or version is not seen, because the
 * tenant filter and row-level security both hide it.
 */
@Service
class ServiceTermsImpl implements ServiceTerms {

    private final SalonServiceRepository services;
    private final TermsRows versions;
    private final TransactionTemplate reading;

    ServiceTermsImpl(SalonServiceRepository services, TermsRows versions, PlatformTransactionManager transactions) {
        this.services = services;
        this.versions = versions;
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
        this.reading.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Optional<ServiceTermsVersion> current(UUID serviceId) {
        TenantContext.require();
        if (serviceId == null) {
            return Optional.empty();
        }
        return reading.execute(status -> services.findAny(serviceId)
                .filter(service -> !service.isDeleted())
                .flatMap(service -> versions.find(service.getCurrentTermsId()).map(terms -> of(service, terms))));
    }

    @Override
    public Optional<ServiceTermsVersion> version(UUID versionId) {
        TenantContext.require();
        if (versionId == null) {
            return Optional.empty();
        }
        return reading.execute(status -> versions.find(versionId)
                .flatMap(terms -> services.findAny(terms.row().getServiceId()).map(service -> of(service, terms))));
    }

    private static ServiceTermsVersion of(SalonService service, TermsRows.Read read) {
        TermsVersion row = read.row();
        TermsVersion.Terms terms = read.terms();
        Ranges ranges = Ranges.of(terms.priceMinor(), terms.segments(), terms.variants());
        return new ServiceTermsVersion(
                service.getId(),
                row.getId(),
                row.getNumber(),
                service.getName(),
                terms.priceMinor(),
                terms.currency(),
                terms.segments(),
                service.isBookableOnline() && !service.isDeleted(),
                terms.variantSetName(),
                terms.variants(),
                ranges.priceMinMinor(),
                ranges.priceMaxMinor(),
                ranges.durationMinMinutes(),
                ranges.durationMaxMinutes(),
                terms.deposit());
    }
}
