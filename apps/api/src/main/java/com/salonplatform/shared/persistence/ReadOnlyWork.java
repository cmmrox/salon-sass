package com.salonplatform.shared.persistence;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * How a provided read interface reads (decision 119, {@code docs/engineering/backend.md}, "Provided interfaces"): in
 * a read-only tenant transaction of its own, never the caller's, and never inside a writing transaction. A caller
 * reads what it needs from another module before it opens its own transaction, as it would ask a remote service; a
 * read inside a writing transaction would either load another module's entities into the caller's unit of work, or
 * hold a second pooled connection while the caller holds its first (a pool of ten, two seconds' wait). A contract
 * that must join its caller's transaction is declared in-process ({@code inprocess}) and does not use this.
 *
 * <p>Every implementation of an interface in a module's root package reads through this, which an architecture test
 * holds.
 */
@Component
public class ReadOnlyWork {

    private final TransactionTemplate reading;

    private final TransactionTemplate repeatable;

    ReadOnlyWork(PlatformTransactionManager transactions) {
        this.reading = template(transactions);
        this.repeatable = template(transactions);
        this.repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * Runs the read in a read-only transaction of its own.
     *
     * @throws IllegalStateException when called inside a writing transaction
     */
    public <T> T read(Supplier<T> work) {
        refuseInsideAWritingTransaction();
        return reading.execute(status -> work.get());
    }

    /**
     * As {@link #read}, in one snapshot: every statement sees the database as the first one did, for an answer
     * assembled from several reads that must agree with each other.
     */
    public <T> T readOneSnapshot(Supplier<T> work) {
        refuseInsideAWritingTransaction();
        return repeatable.execute(status -> work.get());
    }

    private static void refuseInsideAWritingTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("A provided read interface is called inside a writing transaction; read"
                    + " what is needed before opening it, or declare the contract in-process (decision 119)");
        }
    }

    private static TransactionTemplate template(PlatformTransactionManager transactions) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setReadOnly(true);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }
}
