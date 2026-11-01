package com.salonplatform.catalogue.service.impl;

import com.salonplatform.catalogue.entity.SalonService;
import com.salonplatform.catalogue.entity.TermsVersion;
import com.salonplatform.catalogue.service.SalonServices.ServiceView;
import com.salonplatform.catalogue.service.SalonServices.TermsView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A service as the workspace reads it, with its current terms and their variants: two queries for the terms of a
 * whole list. Joins the caller's transaction.
 */
@Component
class ServiceViews {

    private final TermsRows terms;

    ServiceViews(TermsRows terms) {
        this.terms = terms;
    }

    ServiceView of(SalonService service) {
        return of(service, terms.require(service.getCurrentTermsId()));
    }

    List<ServiceView> of(List<SalonService> services) {
        if (services.isEmpty()) {
            return List.of();
        }
        Map<UUID, TermsRows.Read> read =
                terms.all(services.stream().map(SalonService::getCurrentTermsId).toList());
        return services.stream()
                .map(service -> of(service, read.get(service.getCurrentTermsId())))
                .toList();
    }

    static ServiceView of(SalonService service, TermsRows.Read read) {
        TermsVersion row = read.row();
        TermsVersion.Terms held = read.terms();
        return new ServiceView(
                service.getId(),
                service.getName(),
                service.getCategoryId(),
                service.getDescription(),
                service.isBookableOnline(),
                service.getPhotoId(),
                service.isPopular(),
                service.getVersion(),
                new TermsView(
                        row.getId(),
                        row.getNumber(),
                        held.priceMinor(),
                        held.currency(),
                        held.segments(),
                        held.variantSetName(),
                        held.variants(),
                        held.deposit()));
    }
}
