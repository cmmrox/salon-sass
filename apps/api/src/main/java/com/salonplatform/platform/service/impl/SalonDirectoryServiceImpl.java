package com.salonplatform.platform.service.impl;

import com.salonplatform.platform.domain.OperatorPermission;
import com.salonplatform.platform.domain.Paging;
import com.salonplatform.platform.dto.PlanSummary;
import com.salonplatform.platform.dto.SalonDetail;
import com.salonplatform.platform.dto.SalonPage;
import com.salonplatform.platform.dto.SalonSummary;
import com.salonplatform.platform.repository.SalonStore;
import com.salonplatform.platform.repository.SalonStore.Salon;
import com.salonplatform.platform.service.SalonDirectoryService;
import com.salonplatform.shared.web.NotFoundException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class SalonDirectoryServiceImpl implements SalonDirectoryService {

    private final SalonStore salons;
    private final OperatorWork work;
    private final OperatorAccess access;
    private final String platformDomain;

    SalonDirectoryServiceImpl(
            SalonStore salons,
            OperatorWork work,
            OperatorAccess access,
            @Value("${salon.platform-domain}") String platformDomain) {
        this.salons = salons;
        this.work = work;
        this.access = access;
        this.platformDomain = platformDomain.strip().toLowerCase(Locale.ROOT);
    }

    private String hostname(String slug) {
        return slug + "." + platformDomain;
    }

    private static PlanSummary plan(SalonStore.PlanSummary plan) {
        return plan == null ? null : new PlanSummary(plan.id(), plan.key(), plan.name());
    }

    private SalonSummary summary(Salon salon) {
        return new SalonSummary(
                salon.id(),
                salon.name(),
                salon.slug(),
                hostname(salon.slug()),
                salon.status(),
                plan(salon.plan()),
                salon.createdAt());
    }

    private SalonDetail detail(Salon salon) {
        return new SalonDetail(
                salon.id(),
                salon.version(),
                salon.name(),
                salon.slug(),
                hostname(salon.slug()),
                salon.status(),
                salon.country(),
                salon.currency(),
                salon.timeZone(),
                salon.defaultLanguage(),
                salon.contactEmail(),
                salon.contactPhone(),
                salon.address(),
                plan(salon.plan()),
                salon.ownerEmail(),
                salon.createdAt(),
                salon.updatedAt(),
                salon.closedAt(),
                salon.deleteAt(),
                salon.deletedAt());
    }

    @Override
    public SalonPage list(Paging paging) {
        return work.run(() -> {
            access.require(OperatorPermission.SALON_READ);
            List<SalonSummary> items = salons.page(paging.q(), paging.size(), paging.offset()).stream()
                    .map(this::summary)
                    .toList();
            long total = salons.count(paging.q());
            return new SalonPage(items, paging.page(), paging.size(), total);
        });
    }

    @Override
    public SalonDetail get(UUID id) {
        return work.run(() -> {
            access.require(OperatorPermission.SALON_READ);
            return salons.findById(id).map(this::detail).orElseThrow(NotFoundException::new);
        });
    }
}
