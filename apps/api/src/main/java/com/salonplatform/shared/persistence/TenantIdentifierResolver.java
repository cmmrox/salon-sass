package com.salonplatform.shared.persistence;

import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.hibernate.cfg.MultiTenancySettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

/**
 * Tells Hibernate which salon a session belongs to, for {@code @TenantId}: Hibernate then adds
 * {@code tenant_id = <salon>} to every query on a tenant entity and stamps the salon on every insert,
 * refusing one that names another salon.
 *
 * <p>With no salon resolved it answers {@link TenantContext#NO_TENANT}, which no row can carry: every
 * query finds nothing and every insert is refused. A session cannot simply be opened without a
 * tenant, because Hibernate refuses that outright once an entity carries {@code @TenantId}.
 */
@Component
class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID>, HibernatePropertiesCustomizer {

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        return TenantContext.resolved().map(CurrentTenant::id).orElse(TenantContext.NO_TENANT);
    }

    /** Sessions live for one transaction; there is no long-lived session to check. */
    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
    }
}
