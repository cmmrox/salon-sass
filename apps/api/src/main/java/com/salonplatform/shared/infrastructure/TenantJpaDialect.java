package com.salonplatform.shared.infrastructure;

import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.hibernate.Session;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;

/**
 * Starts every transaction the way docs/architecture/multi-tenancy.md requires: the request's
 * limit on database waits, then the salon for row-level security, for this transaction only.
 *
 * <pre>SELECT set_config('app.tenant_id', :tenantId, true);  -- true = this transaction only</pre>
 *
 * <p>With no salon resolved nothing is set, and row-level security shows no row. Because the
 * setting ends with the transaction, a pooled connection never carries one salon into the next
 * transaction (T-4). A failure here fails the transaction's start, which the transaction manager
 * cleans up and reports.
 */
class TenantJpaDialect extends HibernateJpaDialect {

    private static final String SET_TENANT = "SELECT set_config('app.tenant_id', ?, true)";

    private final RequestDatabaseLimit limit;

    TenantJpaDialect(RequestDatabaseLimit limit) {
        this.limit = limit;
    }

    @Override
    public Object beginTransaction(EntityManager entityManager, TransactionDefinition definition)
            throws PersistenceException, SQLException, TransactionException {
        Object transactionData = super.beginTransaction(entityManager, definition);
        CurrentTenant tenant = TenantContext.resolved().orElse(null);
        entityManager.unwrap(Session.class).doWork(connection -> {
            limit.applyTo(connection);
            if (tenant != null) {
                try (PreparedStatement statement = connection.prepareStatement(SET_TENANT)) {
                    statement.setString(1, tenant.id().toString());
                    statement.execute();
                }
            }
        });
        return transactionData;
    }
}
