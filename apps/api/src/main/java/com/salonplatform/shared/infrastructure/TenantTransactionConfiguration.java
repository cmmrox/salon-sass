package com.salonplatform.shared.infrastructure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The application's transaction manager: Spring Boot's own JPA transaction manager, except that every
 * transaction starts through {@link TenantJpaDialect}.
 */
@Configuration(proxyBeanMethods = false)
class TenantTransactionConfiguration {

    @Bean
    PlatformTransactionManager transactionManager(RequestDatabaseLimit limit,
            ObjectProvider<TransactionManagerCustomizers> customizers) {
        TenantTransactionManager transactionManager = new TenantTransactionManager(new TenantJpaDialect(limit));
        customizers.ifAvailable(each -> each.customize(transactionManager));
        return transactionManager;
    }

    /**
     * The transaction manager takes its dialect from the entity manager factory when it is
     * initialised; this puts ours back afterwards.
     */
    static final class TenantTransactionManager extends JpaTransactionManager {

        private final TenantJpaDialect dialect;

        TenantTransactionManager(TenantJpaDialect dialect) {
            this.dialect = dialect;
        }

        @Override
        public void afterPropertiesSet() {
            super.afterPropertiesSet();
            setJpaDialect(dialect);
        }
    }
}
