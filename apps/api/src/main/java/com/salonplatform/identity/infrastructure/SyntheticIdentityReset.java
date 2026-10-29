package com.salonplatform.identity.infrastructure;

import com.salonplatform.shared.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Empties the current salon's identity records for the local fixture hooks: set-based removal, a named
 * direct-SQL exception ({@code docs/engineering/backend.md}, "Persistence and migrations"). Every statement
 * names {@code tenant_id}; row-level security checks it again. The salon's lock row stays.
 */
@Component
@RequiredArgsConstructor
public class SyntheticIdentityReset {

    /** Dependants first. */
    private static final List<String> TABLES = List.of(
            "identity_customer_challenge",
            "identity_mfa_step",
            "identity_mfa_challenge",
            "identity_refresh_history",
            "identity_session",
            "identity_rate_bucket",
            "identity_customer",
            "identity_staff");

    private final JdbcClient jdbc;

    /** Every staff and customer record of the salon, with their sessions and security state. */
    public void removeEveryRecord() {
        UUID salon = TenantContext.require().id();
        for (String table : TABLES) {
            jdbc.sql("DELETE FROM " + table + " WHERE tenant_id = ?")
                    .param(salon)
                    .update();
        }
    }

    /** The salon's customers, with their codes, sessions and code rate buckets. Staff records stay. */
    public void removeCustomerRecords() {
        UUID salon = TenantContext.require().id();
        jdbc.sql("DELETE FROM identity_customer_challenge WHERE tenant_id = ?")
                .param(salon)
                .update();
        jdbc.sql("""
                        DELETE FROM identity_refresh_history
                         WHERE tenant_id = ?
                           AND session_id IN (SELECT id FROM identity_session WHERE tenant_id = ? AND kind = 'customer')
                        """).params(salon, salon).update();
        jdbc.sql("DELETE FROM identity_session WHERE tenant_id = ? AND kind = 'customer'")
                .param(salon)
                .update();
        jdbc.sql("DELETE FROM identity_customer WHERE tenant_id = ?")
                .param(salon)
                .update();
        jdbc.sql("DELETE FROM identity_rate_bucket WHERE tenant_id = ? AND dimension LIKE 'customer-%'")
                .param(salon)
                .update();
    }
}
