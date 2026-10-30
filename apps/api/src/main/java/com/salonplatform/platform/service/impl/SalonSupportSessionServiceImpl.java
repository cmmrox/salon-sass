package com.salonplatform.platform.service.impl;

import com.salonplatform.identity.Principal;
import com.salonplatform.identity.Principals;
import com.salonplatform.platform.domain.Paging;
import com.salonplatform.platform.dto.SalonSupportSession;
import com.salonplatform.platform.dto.SalonSupportSessionPage;
import com.salonplatform.platform.dto.SupportSessionView;
import com.salonplatform.platform.repository.SupportSessionStore;
import com.salonplatform.platform.repository.SupportSessionStore.Session;
import com.salonplatform.platform.service.SalonSupportSessionService;
import com.salonplatform.shared.persistence.PlatformDatabase;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class SalonSupportSessionServiceImpl implements SalonSupportSessionService {

    private final PlatformDatabase database;
    private final SupportSessionStore store;
    private final OperatorWork work;
    private final SupportSessionRecords records;
    private final Principals principals;
    private final Clock clock;

    SalonSupportSessionServiceImpl(
            PlatformDatabase database,
            SupportSessionStore store,
            OperatorWork work,
            SupportSessionRecords records,
            Principals principals,
            Clock clock) {
        this.database = database;
        this.store = store;
        this.work = work;
        this.records = records;
        this.principals = principals;
        this.clock = clock;
    }

    @Override
    public SalonSupportSessionPage salonList(Paging paging) {
        UUID salonId = TenantContext.require().id();
        principals.require("support.manage");
        return database.inTransaction(status -> {
            Instant now = clock.instant();
            List<SalonSupportSession> items = store.pageOfSalon(salonId, paging.size(), paging.offset()).stream()
                    .map(each -> {
                        SupportSessionView view = records.view(each, now);
                        return new SalonSupportSession(
                                each.id(),
                                each.operatorName(),
                                each.reason(),
                                each.startedAt(),
                                each.expiresAt(),
                                view.endedAt(),
                                view.endedBy(),
                                view.state());
                    })
                    .toList();
            long total = store.countOfSalon(salonId);
            return new SalonSupportSessionPage(items, paging.page(), paging.size(), total);
        });
    }

    @Override
    public void salonRevoke(UUID sessionId) {
        UUID salonId = TenantContext.require().id();
        Principal owner = principals.require("support.manage");
        work.run(() -> {
            Session row = records.lockRow(sessionId)
                    .filter(each -> each.salonId().equals(salonId))
                    .orElseThrow(NotFoundException::new);
            records.finish(
                    row,
                    "owner",
                    owner.id().toString(),
                    "staff",
                    owner.id().toString(),
                    "staff",
                    owner.id().toString());
            return null;
        });
    }
}
