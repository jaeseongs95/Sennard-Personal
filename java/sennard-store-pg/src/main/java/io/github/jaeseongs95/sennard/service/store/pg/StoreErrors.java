// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import javax.sql.DataSource;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;
import org.springframework.jdbc.support.SQLExceptionTranslator;

import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import io.github.jaeseongs95.sennard.service.store.StoreException.Effect;

/**
 * Spring's data access exception to Sennard reason, in this one place. Spring's own translator reads the database's
 * answer (its error codes, chosen by the database product the DataSource reports, then the SQL state, then the JDBC exception subclass) and gives
 * a {@link DataAccessException} of one kind: a duplicate key, a lock that was not free, a deadlock victim, a query that ran out of time. This class
 * only turns that kind into the reason the callers of the store know. The server's own message is not copied into the exception message: it can
 * carry values from the statement.
 */
final class StoreErrors {
    /** Spring's translator for what has no DataSource yet (a start-up connection): the JDBC exception subclass, then the standard SQL state. */
    private static final SQLExceptionTranslator STANDARD = new SQLExceptionSubclassTranslator();
    /** What Spring's translators call the operation of a failure that has no statement: the start-up and migration connections. */
    private static final String CONNECTION_TASK = "store connection";

    private StoreErrors() {
    }

    /**
     * The SQL exception translator of the store: statements, and commit and rollback (where a lost connection leaves the result unknown). The translator
     * Spring would use for the pool is made on the first failure, not before, so a pool that is still being prepared is not asked for its product name.
     */
    static SQLExceptionTranslator translator(DataSource dataSource) {
        return new SQLExceptionTranslator() {
            private volatile SQLExceptionTranslator spring;

            @Override
            public DataAccessException translate(String task, String sql, SQLException failure) {
                SQLExceptionTranslator translator = spring;
                if (translator == null) {
                    translator = new SQLErrorCodeSQLExceptionTranslator(dataSource);
                    spring = translator;
                }
                DataAccessException translated = translator.translate(task, sql, failure);
                return map(translated == null ? new DataAccessResourceFailureException("", failure) : translated, "JDBC commit".equals(task));
            }
        };
    }

    /**
     * The Sennard reasons a data access exception can map to, each with its fixed message. The constant is the {@link StoreCode}
     * the {@link StoreException} carries (the same name). The classes are Spring's, most specific first.
     */
    enum Reason {
        STORE_BUSY("A lock was not free."),
        STORE_CONFLICT("The statement conflicted with another transaction (a serialization failure or a deadlock victim)."),
        STORE_UNIQUE_VIOLATION("A unique key already exists."),
        STORE_TIMEOUT("The statement or the transaction ran past its time limit."),
        STORE_RESOURCE_EXHAUSTED("The database has no room or memory left."),
        INVALID_INPUT("A value the database cannot store."),
        STORE_RESULT_UNKNOWN("The connection was lost while committing; the result is unknown."),
        STORE_UNREACHABLE("The database cannot be reached."),
        STORE_FAILURE("The database statement failed.");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        StoreCode code() {
            return StoreCode.valueOf(name());
        }

        /** The reason of a failure Spring already classified. */
        static Reason of(DataAccessException failure) {
            return switch (failure) {
                case DuplicateKeyException duplicate -> STORE_UNIQUE_VIOLATION;
                case DataIntegrityViolationException integrity -> INVALID_INPUT;
                case CannotAcquireLockException busy -> STORE_BUSY;
                case ConcurrencyFailureException conflict -> STORE_CONFLICT; // a deadlock victim, a serialization failure: Spring's classes for them are deprecated, this is their parent
                case QueryTimeoutException timeout -> STORE_TIMEOUT;
                case DataAccessResourceFailureException resource -> resource(resource);
                default -> STORE_FAILURE;
            };
        }

        /** A resource failure of Spring's is a connection that is gone or a database that is full; Spring has one class for both, so the SQL state it carries tells them apart. */
        private static Reason resource(DataAccessResourceFailureException failure) {
            if (failure.getCause() instanceof SQLException sql && sql.getSQLState() != null) {
                String state = sql.getSQLState();
                if ("53100".equals(state) || "53200".equals(state)) {
                    return STORE_RESOURCE_EXHAUSTED;
                }
                if ("57014".equals(state)) {
                    return STORE_TIMEOUT;
                }
            }
            return STORE_UNREACHABLE;
        }
    }

    /** @param commitStarted true when the failure came while COMMIT was in flight, so a lost connection leaves the result unknown */
    static StoreException map(DataAccessException failure, boolean commitStarted) {
        if (failure instanceof StoreException already) { // a StoreException is a DataAccessException too: it already has its reason
            return already;
        }
        Reason found = Reason.of(failure);
        Reason reason = found == Reason.STORE_UNREACHABLE && commitStarted ? Reason.STORE_RESULT_UNKNOWN : found;
        Effect effect = reason == Reason.STORE_RESULT_UNKNOWN ? Effect.UNKNOWN : Effect.NONE;
        String state = failure.getCause() instanceof SQLException sql ? sql.getSQLState() : null;
        return new StoreException(reason.code(), state, effect, reason.message, failure);
    }

    /** A SQL failure from a connection that is not the pool's (the start-up connection, the migration connection). */
    static StoreException map(SQLException failure, boolean commitStarted) {
        DataAccessException translated = STANDARD.translate(CONNECTION_TASK, null, failure);
        return map(translated == null ? new UncategorizedSQLException(CONNECTION_TASK, null, failure) : translated, commitStarted);
    }

    /** A pool that cannot lend a connection: exhausted when no connection failure stands behind it, unreachable when one does. */
    static StoreException map(SQLTransientConnectionException failure, int waiting) {
        if (failure.getCause() != null || failure.getNextException() != null) {
            return new StoreException(StoreCode.STORE_UNREACHABLE, null, Effect.NONE, Reason.STORE_UNREACHABLE.message, failure);
        }
        return new StoreException(StoreCode.STORE_POOL_EXHAUSTED, null, Effect.NONE,
                "No connection was free in time (threadsAwaitingConnection=" + waiting + ").", failure);
    }
}
