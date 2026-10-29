package com.salonplatform.shared.persistence;

import jakarta.persistence.EntityManagerFactory;
import java.io.Serial;
import java.sql.Connection;
import org.hibernate.Session;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The application's transaction manager: Spring Boot's own JPA transaction manager, except that every
 * transaction starts through {@link TenantJpaDialect}, and that a rollback on a connection that is
 * already gone does not hide why the work failed.
 */
@Configuration(proxyBeanMethods = false)
class TenantTransactionConfiguration {

    @Bean
    PlatformTransactionManager transactionManager(
            RequestDatabaseLimit limit, ObjectProvider<TransactionManagerCustomizers> customizers) {
        TenantTransactionManager transactionManager = new TenantTransactionManager(new TenantJpaDialect(limit));
        customizers.ifAvailable(each -> each.customize(transactionManager));
        return transactionManager;
    }

    static final class TenantTransactionManager extends JpaTransactionManager {

        @Serial
        private static final long serialVersionUID = 1L;

        private final TenantJpaDialect dialect;

        TenantTransactionManager(TenantJpaDialect dialect) {
            this.dialect = dialect;
        }

        /**
         * The transaction manager takes its dialect from the entity manager factory when it is
         * initialised; this puts ours back afterwards.
         */
        @Override
        public void afterPropertiesSet() {
            super.afterPropertiesSet();
            setJpaDialect(dialect);
        }

        /**
         * When work fails because the database went silent, the driver gives the connection up, and
         * the rollback that follows fails too: there is no connection left to roll back on. The
         * database discards the transaction with the connection, so nothing is left undone, and the
         * failure worth reporting is the first one. Only that case is let pass; a rollback that
         * fails on a connection that is still open is reported as it always was.
         */
        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            try {
                super.doRollback(status);
            } catch (RuntimeException ex) {
                if (!connectionIsGone()) {
                    throw ex;
                }
                logger.debug("Nothing to roll back: the connection is already closed");
            }
        }

        private boolean connectionIsGone() {
            EntityManagerFactory factory = getEntityManagerFactory();
            if (factory == null
                    || !(TransactionSynchronizationManager.getResource(factory)
                            instanceof EntityManagerHolder holder)) {
                return false;
            }
            try {
                return Boolean.TRUE.equals(
                        holder.getEntityManager().unwrap(Session.class).doReturningWork(Connection::isClosed));
            } catch (RuntimeException ex) {
                return false;
            }
        }
    }
}
