// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.dao.DuplicateKeyException;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.IntegrityCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.service.settings.SettingsCode;
import io.github.jaeseongs95.sennard.service.settings.SettingsException;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;

/** What {@link PersistenceErrors} makes of the store's refusals: the one that has a wording of its own is stated as an input failure, the others are an unavailable store. */
class PersistenceErrorsTest {
    /** The refusal of H2 as the business database reaches the caller as INVALID_INPUT with its own reason, whose wording is in the English and Korean catalogs (ErrorMessagesTest). */
    @Test
    void h2AsTheBusinessDatabaseIsStatedAsAnInputFailureWithItsOwnReason() {
        StoreException refusal = new StoreException(StoreCode.BUSINESS_DATABASE_NOT_H2, "The JDBC URL names H2.");
        RuntimeException translated = PersistenceErrors.translate(refusal);
        InvalidInputException input = assertInstanceOf(InvalidInputException.class, translated);
        assertEquals(InputCode.BUSINESS_DATABASE_NOT_H2, input.code());
        assertSame(refusal, input.getCause());
    }

    /** A foreign key violation is a reference violation by the standard SQLSTATE (PostgreSQL) and by the error code of MariaDB and MySQL, whose SQLSTATE is the class-wide 23000. */
    @Test
    void aForeignKeyViolationIsAReferenceViolationOnEveryDbms() {
        for (SQLException failure : List.of(new SQLException("fk", "23503", 0), new SQLException("fk", "23000", 1452), new SQLException("fk", "23000", 1451))) {
            IntegrityException translated = assertInstanceOf(IntegrityException.class, PersistenceErrors.translate(new DataIntegrityViolationException("fk", failure)));
            assertEquals(IntegrityCode.REFERENCE_VIOLATION, translated.code(), failure.getSQLState() + "/" + failure.getErrorCode());
        }
        IntegrityException other = assertInstanceOf(IntegrityException.class, PersistenceErrors.translate(new DataIntegrityViolationException("check", new SQLException("check", "23000", 4025))));
        assertEquals(IntegrityCode.VALUE_NOT_ALLOWED, other.code());
    }

    /**
     * The settings store's refusals (the passkey registry, the records before the link) are stated like the others: every code has an answer of its own (the switch has no default, so
     * this is the one place that goes through all of them; the entrance's test needs a representative only).
     */
    @Test
    void everyRefusalOfTheSettingsStoreIsAnSennardException() {
        for (SettingsCode code : SettingsCode.values()) {
            SettingsException refusal = new SettingsException(code, "refused");
            SennardException translated = assertInstanceOf(SennardException.class, PersistenceErrors.translate(refusal), code.name());
            assertSame(refusal, translated.getCause(), code.name());
        }
        assertEquals(ConflictCode.DUPLICATE, assertInstanceOf(ConflictException.class, PersistenceErrors.translate(new SettingsException(SettingsCode.PASSKEY_EXISTS, "other key"))).code());
        assertEquals(InputCode.ADMIN_CHANGE_INVALID, assertInstanceOf(InvalidInputException.class, PersistenceErrors.translate(new SettingsException(SettingsCode.PRE_LINK_CLOSED, "closed"))).code());
        assertInstanceOf(UnavailableException.class, PersistenceErrors.translate(new SettingsException(SettingsCode.SETTINGS_UNAVAILABLE, "down")));
    }

    /**
     * What Spring's data access exceptions are, for the application layer ({@code translate}) and for the HTTP boundary ({@code ofDataAccess}) alike: a race with another writer is the
     * retryable conflict, a unique key is a duplicate, a constraint is an integrity refusal and anything else is an unavailable store. The old store exception, which is a
     * {@code DataAccessException} too, is read by its code and not taken for a failed store.
     */
    @Test
    void aDataAccessExceptionIsTheSameAnswerAtBothEntrances() {
        ConcurrencyFailureException race = new ConcurrencyFailureException("CONCURRENT_UPDATE_1");
        ConflictException conflict = assertInstanceOf(ConflictException.class, PersistenceErrors.translate(race));
        assertEquals(ConflictCode.CONCURRENT_UPDATE, conflict.code());
        assertSame(race, conflict.getCause());
        assertEquals(ConflictCode.CONCURRENT_UPDATE, PersistenceErrors.ofDataAccess(race).code());
        assertEquals(ConflictCode.DUPLICATE, PersistenceErrors.ofDataAccess(new DuplicateKeyException("key")).code());
        assertEquals(IntegrityCode.VALUE_NOT_ALLOWED, PersistenceErrors.ofDataAccess(new DataIntegrityViolationException("check")).code());
        SennardException down = PersistenceErrors.ofDataAccess(new DataRetrievalFailureException("jdbc:secret-store-detail"));
        assertEquals(UnavailableCode.STORE_UNAVAILABLE, down.code());
        assertEquals("STORE_FAILURE", down.details().get("reason"));
        assertEquals(ConflictCode.CONCURRENT_UPDATE, PersistenceErrors.ofDataAccess(new StoreException(StoreCode.STORE_CONFLICT, "raced")).code());
    }

    @Test
    void anotherConfigurationRefusalIsAnUnavailableStore() {
        StoreException other = new StoreException(StoreCode.INVALID_DATABASE_CONFIG, "The JDBC URL is required.");
        assertInstanceOf(UnavailableException.class, PersistenceErrors.translate(other));
    }
}
