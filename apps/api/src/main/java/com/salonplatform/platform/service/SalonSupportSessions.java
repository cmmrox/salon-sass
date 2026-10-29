package com.salonplatform.platform.service;

import com.salonplatform.identity.Principal;
import com.salonplatform.identity.Principals;
import com.salonplatform.platform.dto.SalonSupportSession;
import com.salonplatform.platform.dto.SalonSupportSessionPage;
import com.salonplatform.platform.dto.SupportSessionView;
import com.salonplatform.platform.repository.SupportSessionStore;
import com.salonplatform.platform.repository.SupportSessionStore.Session;
import com.salonplatform.shared.persistence.PlatformDatabase;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * A salon's Owner sees and revokes the salon's support sessions on the salon's own host (PLT-07;
 * docs/architecture/operator-console.md, "Support sessions"). Another salon's session is not found.
 */
@Service
public class SalonSupportSessions {

    private final PlatformDatabase database;
    private final SupportSessionStore store;
    private final OperatorWork work;
    private final SupportSessionRecords records;
    private final Principals principals;
    private final Clock clock;

    public SalonSupportSessions(
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

    /** The salon's own sessions, active and past, for its Owner. */
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

    /** The Owner revokes an active session of the salon; another salon's is not found. */
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
