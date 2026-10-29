package com.salonplatform.platform.infrastructure;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Local test hook only: how many of one salon's outbox messages are in each delivery status, counts and
 * nothing of a message. Read on the tenant pool inside the caller's tenant transaction with that salon
 * set, so row-level security limits it to that salon as well as its {@code tenant_id = ?} predicate.
 * Named direct-SQL exception 5 of docs/engineering/backend.md, a read model of counts.
 */
@Component
@Profile("local")
public class SalonMessageCounts {

    private final JdbcClient tenantJdbc;

    SalonMessageCounts(JdbcClient tenantJdbc) {
        this.tenantJdbc = tenantJdbc;
    }

    /** The salon's message count by status; a status with no message is absent. */
    public Map<String, Long> byStatus(UUID salonId) {
        Map<String, Long> counts = new HashMap<>();
        tenantJdbc
                .sql("SELECT status, count(*) AS n FROM outbox_message WHERE tenant_id = ? GROUP BY status")
                .param(salonId)
                .query((row, n) -> Map.entry(row.getString("status"), row.getLong("n")))
                .list()
                .forEach(count -> counts.put(count.getKey(), count.getValue()));
        return counts;
    }
}
