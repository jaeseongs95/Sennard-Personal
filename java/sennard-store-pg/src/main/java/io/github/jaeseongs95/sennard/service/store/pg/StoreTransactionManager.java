// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;

import com.zaxxer.hikari.HikariDataSource;

import io.github.jaeseongs95.sennard.service.store.StoreException;

/**
 * Spring's {@link JdbcTransactionManager} with the translator that gives a failure of commit or rollback its Sennard reason, and nothing else. The time every transaction has (no
 * statement of it starts past that, and a statement is cut when the time left is over, JDBC's query timeout) is the edition's setting, {@code spring.transaction.default-timeout},
 * which Boot's {@code TransactionManagerCustomizers} sets on this manager ({@link StoreTransactionConfiguration}); this class holds no number.
 *
 * <p>Waiting for a lock has no limit of its own: it ends with the transaction's time and the statement's cut is a {@code STORE_TIMEOUT}. The one thing
 * it adds to Spring's behaviour is on begin: a connection that cannot be had is the store failure itself, with its code (the database is still being prepared,
 * the pool is exhausted); Spring would hide it behind a generic {@code CannotCreateTransactionException}.
 */
public final class StoreTransactionManager extends JdbcTransactionManager {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(StoreTransactionManager.class);

    public StoreTransactionManager(DataSource dataSource) {
        super(dataSource);
        setExceptionTranslator(StoreErrors.translator(dataSource));
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        try {
            super.doBegin(transaction, definition);
        } catch (CannotCreateTransactionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof StoreException storeFailure) {
                throw storeFailure;
            }
            if (cause instanceof SQLTransientConnectionException exhausted) {
                throw StoreErrors.map(exhausted, waitingForConnection());
            }
            if (cause instanceof SQLException sqlFailure) {
                throw StoreErrors.map(sqlFailure, false);
            }
            throw failure;
        }
    }

    /** How many are waiting behind this one, for the failure message; -1 when the data source is no Hikari pool (or wraps none) and so cannot say. */
    private int waitingForConnection() {
        DataSource source = obtainDataSource();
        try {
            if (!source.isWrapperFor(HikariDataSource.class)) {
                return -1;
            }
            var pool = source.unwrap(HikariDataSource.class).getHikariPoolMXBean();
            return pool == null ? -1 : pool.getThreadsAwaitingConnection();
        } catch (SQLException unwrapFailed) {
            LOG.warn("The pool of the store could not be asked how many threads wait for a connection.", unwrapFailed);
            return -1;
        }
    }
}
