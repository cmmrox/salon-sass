package com.salonplatform.catalogue.repository;

import com.salonplatform.shared.tenancy.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Creates a branch's Popular setting the first time it is needed, as "Chosen by you" at version 0, with an insert
 * that does nothing when the row exists: a repository cannot express it, and two first uses at once must both go on
 * to wait for the row's lock rather than fail on its key (docs/engineering/backend.md, the per-salon lock row
 * exception). It names {@code tenant_id}; row-level security is its only guard. Joins the caller's transaction.
 */
@Component
public class PopularSettingRows {

    private static final String CREATE_ROW = """
            INSERT INTO catalogue_popular_setting (tenant_id, id, branch_id, mode, version, created_at, updated_at)
            VALUES (?, ?, ?, 'chosen', 0, ?, ?) ON CONFLICT (tenant_id, branch_id) DO NOTHING
            """;

    private final JdbcClient jdbc;

    PopularSettingRows(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Makes sure the request's salon has the branch's setting row. */
    public void ensure(UUID branchId, Instant at) {
        UUID salon = TenantContext.require().id();
        jdbc.sql(CREATE_ROW)
                .params(salon, UUID.randomUUID(), branchId, Timestamp.from(at), Timestamp.from(at))
                .update();
    }
}
