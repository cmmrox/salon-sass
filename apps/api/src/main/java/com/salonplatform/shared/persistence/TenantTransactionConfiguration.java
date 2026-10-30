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
 * transaction starts through {@link TenantJpaDialect}, and that a transaction ending on a connection
 * that is already gone does not hide that the database is unavailable.
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
         * A transaction that ends on a connection the driver gave up (the database went silent or away
         * while the work held it) answers that the database is unavailable, whatever the work concluded
         * (BUG-F02-015). Nothing it read can be trusted: Hibernate's {@code find} answers "no such row"
         * for a lost connection once the transaction is marked rollback-only (HHH-12472), so a lookup
         * reads as not found and the work may answer 401 or 404, or even finish normally. Its commit or
         * rollback then fails too, and says nothing of why. The database discards the transaction with
         * the connection, so nothing is left undone. A commit or rollback that fails on a connection
         * still open is reported as it always was.
         */
        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            try {
                super.doCommit(status);
            } catch (RuntimeException ex) {
                if (!connectionIsGone()) {
                    throw ex;
                }
                throw LostConnection.atTransactionEnd(ex);
            }
        }

        /** The same as {@link #doCommit}: on a connection already gone, the database is unavailable. */
        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            try {
                super.doRollback(status);
            } catch (RuntimeException ex) {
                if (!connectionIsGone()) {
                    throw ex;
                }
                throw LostConnection.atTransactionEnd(ex);
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
