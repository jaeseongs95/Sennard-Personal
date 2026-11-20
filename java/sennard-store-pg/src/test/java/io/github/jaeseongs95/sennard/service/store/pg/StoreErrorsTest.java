// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;

import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;

/**
 * Spring's data access exception to Sennard reason. The store no longer reads SQLSTATE itself: Spring's translator names the kind of failure and
 * {@link StoreErrors} turns the kind into the reason. The cases are every kind the mapping names, the two resource failures told apart by the SQL
 * state they carry, a lost connection with and without COMMIT in flight, an exception that already is a {@link StoreException}, and the path of a
 * raw {@link SQLException} (the start-up and migration connections, which have no DataSource to ask for vendor error codes).
 */
class StoreErrorsTest {
    private static StoreCode code(DataAccessException failure, boolean commitStarted) {
        return StoreErrors.map(failure, commitStarted).code();
    }

    private static DataAccessResourceFailureException resourceFailure(String sqlState) {
        return new DataAccessResourceFailureException("x", new SQLException("x", sqlState));
    }

    /** S1: each kind of Spring exception gives the reason the callers of the store know. */
    @Test
    void eachSpringExceptionKindGivesItsReason() {
        assertEquals(StoreCode.STORE_UNIQUE_VIOLATION, code(new DuplicateKeyException("x"), false));
        assertEquals(StoreCode.STORE_BUSY, code(new CannotAcquireLockException("x"), false));
        assertEquals(StoreCode.STORE_CONFLICT, code(new ConcurrencyFailureException("x"), false));
        assertEquals(StoreCode.STORE_CONFLICT, code(new PessimisticLockingFailureException("x"), false));
        assertEquals(StoreCode.STORE_TIMEOUT, code(new QueryTimeoutException("x"), false));
        assertEquals(StoreCode.INVALID_INPUT, code(new DataIntegrityViolationException("x"), false));
        assertEquals(StoreCode.STORE_FAILURE, code(new InvalidDataAccessApiUsageException("x"), false));
    }

    /** S1: a duplicate key is a data integrity violation too, and the more specific reason wins. */
    @Test
    void aDuplicateKeyIsNotReportedAsInvalidInput() {
        assertEquals(StoreCode.STORE_UNIQUE_VIOLATION, code(new DuplicateKeyException("x", new SQLException("x", "23505")), false));
        assertEquals(StoreCode.INVALID_INPUT, code(new DataIntegrityViolationException("x", new SQLException("x", "22021")), false));
    }

    /** S1: Spring has one class for a gone connection and a full database; the SQL state its cause carries tells them apart. */
    @Test
    void aResourceFailureIsToldApartBySqlState() {
        assertEquals(StoreCode.STORE_RESOURCE_EXHAUSTED, code(resourceFailure("53100"), false));
        assertEquals(StoreCode.STORE_RESOURCE_EXHAUSTED, code(resourceFailure("53200"), false));
        assertEquals(StoreCode.STORE_TIMEOUT, code(resourceFailure("57014"), false));
        assertEquals(StoreCode.STORE_UNREACHABLE, code(resourceFailure("57P01"), false));
        assertEquals(StoreCode.STORE_UNREACHABLE, code(resourceFailure("53300"), false));
        assertEquals(StoreCode.STORE_UNREACHABLE, code(resourceFailure(null), false));
        assertEquals(StoreCode.STORE_UNREACHABLE, code(new DataAccessResourceFailureException("no cause"), false));
    }

    /** S2: a lost connection is unreachable, or an unknown result when COMMIT was in flight. */
    @Test
    void aLostConnectionIsUnreachableUnlessCommitWasInFlight() {
        for (String state : new String[] {"57P01", "57P02", "57P03", "53300", "08006", "08001"}) {
            StoreException notCommitting = StoreErrors.map(resourceFailure(state), false);
            assertEquals(StoreCode.STORE_UNREACHABLE, notCommitting.code(), state);
            assertEquals(StoreException.Effect.NONE, notCommitting.effect(), state);
            StoreException committing = StoreErrors.map(resourceFailure(state), true);
            assertEquals(StoreCode.STORE_RESULT_UNKNOWN, committing.code(), state);
            assertEquals(StoreException.Effect.UNKNOWN, committing.effect(), state);
            assertEquals(state, committing.sqlState(), state);
        }
    }

    /** S2: COMMIT in flight changes only a lost connection; a failure that says what happened keeps its reason and its effect. */
    @Test
    void commitInFlightDoesNotChangeAFailureThatIsNotALostConnection() {
        assertEquals(StoreCode.STORE_UNIQUE_VIOLATION, code(new DuplicateKeyException("x"), true));
        assertEquals(StoreCode.STORE_CONFLICT, code(new ConcurrencyFailureException("x"), true));
        assertEquals(StoreCode.STORE_RESOURCE_EXHAUSTED, code(resourceFailure("53100"), true));
        assertEquals(StoreException.Effect.NONE, StoreErrors.map(new DuplicateKeyException("x"), true).effect());
    }

    /** S3: a kind the mapping does not know is a plain failure; an exception that already is a StoreException is returned as it is. */
    @Test
    void anUnknownKindIsAPlainFailureAndAStoreExceptionStaysAsItIs() {
        StoreException unknown = StoreErrors.map(new InvalidDataAccessApiUsageException("x"), true);
        assertEquals(StoreCode.STORE_FAILURE, unknown.code());
        assertEquals(StoreException.Effect.NONE, unknown.effect());
        StoreException already = new StoreException(StoreCode.STORE_PREPARING, "starting");
        assertSame(already, StoreErrors.map(already, true));
        assertSame(already, StoreErrors.map(already, false));
    }

    /** The message is the reason's fixed text: the server's own message can carry values from the statement and is not copied. */
    @Test
    void theServersMessageIsNotCopiedIntoTheReason() {
        StoreException mapped = StoreErrors.map(new DuplicateKeyException("x", new SQLException("Key (secret)=(value) already exists.", "23505")), false);
        assertEquals("STORE_UNIQUE_VIOLATION: A unique key already exists.", mapped.getMessage());
        assertEquals("23505", mapped.sqlState());
    }

    /**
     * A raw SQLException of a connection that is not the pool's goes through Spring's standard translator (the JDBC exception subclass, then the SQL
     * state class), with no vendor error code table: so a unique violation is told by 23505 only if Spring's SQL state translator knows it. The
     * values here are what Spring 7's SQLExceptionSubclassTranslator gives, checked against the library. A serialization failure (40001) is left out
     * on purpose: this path gives it a different Spring kind than the DataSource path does.
     */
    @Test
    void aRawSqlExceptionIsMappedThroughSpringsStandardTranslator() {
        assertEquals(StoreCode.STORE_UNIQUE_VIOLATION, StoreErrors.map(new SQLException("x", "23505"), false).code());
        assertEquals(StoreCode.INVALID_INPUT, StoreErrors.map(new SQLException("x", "22021"), false).code());
        assertEquals(StoreCode.STORE_CONFLICT, StoreErrors.map(new SQLException("x", "40P01"), false).code());
        assertEquals(StoreCode.STORE_TIMEOUT, StoreErrors.map(new SQLException("x", "57014"), false).code());
        assertEquals(StoreCode.STORE_RESOURCE_EXHAUSTED, StoreErrors.map(new SQLException("x", "53100"), false).code());
        assertEquals(StoreCode.STORE_UNREACHABLE, StoreErrors.map(new SQLException("x", "57P01"), false).code());
        assertEquals(StoreCode.STORE_RESULT_UNKNOWN, StoreErrors.map(new SQLException("x", "57P01"), true).code());
        assertEquals(StoreCode.STORE_UNREACHABLE, StoreErrors.map(new SQLException("x", "08006"), false).code());
        assertEquals(StoreCode.STORE_RESULT_UNKNOWN, StoreErrors.map(new SQLException("x", "08006"), true).code());
    }

    /** A raw SQLException Spring cannot classify (no SQL state, or a state class it does not know) is a plain failure. */
    @Test
    void aRawSqlExceptionSpringCannotClassifyIsAPlainFailure() {
        assertEquals(StoreCode.STORE_FAILURE, StoreErrors.map(new SQLException("x", "42P01"), false).code());
        assertEquals(StoreCode.STORE_FAILURE, StoreErrors.map(new SQLException("x", "42P01"), true).code());
        assertEquals(StoreCode.STORE_FAILURE, StoreErrors.map(new SQLException("x", "55P03"), false).code());
        assertEquals(StoreCode.STORE_FAILURE, StoreErrors.map(new SQLException("x", (String) null), false).code());
        assertEquals(StoreException.Effect.NONE, StoreErrors.map(new SQLException("x", "42P01"), true).effect());
        assertEquals(StoreException.Effect.UNKNOWN, StoreErrors.map(new SQLException("x", "57P01"), true).effect());
    }
}
