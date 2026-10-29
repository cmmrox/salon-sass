package com.salonplatform.identity.repository;

import com.salonplatform.shared.tenancy.TenantContext;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Erases a deleted customer's codes and sessions in the current salon: set-based erasure, a named direct-SQL
 * exception ({@code docs/engineering/backend.md}, "Persistence and migrations"). Every statement names
 * {@code tenant_id}; row-level security checks it again.
 */
@Component
@RequiredArgsConstructor
public class CustomerDataErasure {

    private final JdbcClient jdbc;

    /** Every code sent to the number, whatever its state. */
    public void eraseChallengesOfNumber(String numberHash) {
        jdbc.sql("DELETE FROM identity_customer_challenge WHERE tenant_id = ? AND number_hash = ?")
                .params(TenantContext.require().id(), numberHash)
                .update();
    }

    /** Every session of the customer, with the refresh credentials they rotated away from. */
    public void eraseSessionsOf(UUID customerId) {
        UUID salon = TenantContext.require().id();
        jdbc.sql("""
                        DELETE FROM identity_refresh_history
                         WHERE tenant_id = ?
                           AND session_id IN (SELECT id FROM identity_session
                                               WHERE tenant_id = ? AND customer_account_id = ? AND kind = 'customer')
                        """).params(salon, salon, customerId).update();
        jdbc.sql("DELETE FROM identity_session WHERE tenant_id = ? AND customer_account_id = ? AND kind = 'customer'")
                .params(salon, customerId)
                .update();
    }
}
