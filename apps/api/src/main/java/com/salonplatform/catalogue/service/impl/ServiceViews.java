package com.salonplatform.catalogue.service.impl;

import com.salonplatform.catalogue.entity.SalonService;
import com.salonplatform.catalogue.entity.TermsVersion;
import com.salonplatform.catalogue.repository.TermsVersionRepository;
import com.salonplatform.catalogue.service.SalonServices.ServiceView;
import com.salonplatform.catalogue.service.SalonServices.TermsView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * A service as the workspace reads it, with its current terms: one query for the terms of a whole list. Joins the
 * caller's transaction.
 */
@Component
class ServiceViews {

    private final TermsVersionRepository versions;

    ServiceViews(TermsVersionRepository versions) {
        this.versions = versions;
    }

    ServiceView of(SalonService service) {
        TermsVersion terms = versions.findVersion(service.getCurrentTermsId()).orElseThrow();
        return of(service, terms);
    }

    List<ServiceView> of(List<SalonService> services) {
        if (services.isEmpty()) {
            return List.of();
        }
        Map<UUID, TermsVersion> terms =
                versions
                        .findVersions(services.stream()
                                .map(SalonService::getCurrentTermsId)
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(TermsVersion::getId, Function.identity()));
        return services.stream()
                .map(service -> of(service, terms.get(service.getCurrentTermsId())))
                .toList();
    }

    static ServiceView of(SalonService service, TermsVersion terms) {
        return new ServiceView(
                service.getId(),
                service.getName(),
                service.getCategoryId(),
                service.getDescription(),
                service.isBookableOnline(),
                service.getPhotoId(),
                service.getVersion(),
                new TermsView(
                        terms.getId(),
                        terms.getNumber(),
                        terms.getPriceMinor(),
                        terms.getCurrency(),
                        terms.segments()));
    }
}
