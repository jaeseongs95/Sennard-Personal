// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.support;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
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
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

/**
 * Where what Spring's data access and Jakarta Validation throw becomes an {@link SennardException}: the one translation of the application layer, run by
 * {@link ErrorTranslationAdvisor} around every service method. A service never catches a data access exception to make its own; it lets it through.
 *
 * <p>The mapping follows SQLSTATE through Spring's own exception hierarchy: 23505 is a {@code DuplicateKeyException}, 40001 and 40P01 are
 * {@code ConcurrencyFailureException}s, 23503 (a foreign key) and 23514 (a CHECK) are {@code DataIntegrityViolationException}s. The commit of a transaction is
 * translated by the transaction manager with the older store translator, so its {@code StoreException} (sennard-api) is read by its code until the
 * stores of phase 5 are rewritten and that class is gone.
 */
public final class PersistenceErrors {
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    /**
     * MariaDB and MySQL answer a foreign key violation with the class-wide SQLSTATE 23000 (the one of every integrity violation) and tell it by their error code: 1451, a parent row
     * that is referenced (ER_ROW_IS_REFERENCED_2), and 1452, a child row whose parent is missing (ER_NO_REFERENCED_ROW_2).
     */
    private static final String INTEGRITY_CLASS = "23000";
    private static final Set<Integer> MYSQL_FAMILY_FOREIGN_KEY_CODES = Set.of(1451, 1452);
    /** The old store translator's codes (sennard-api's StoreException, until the stores of phase 5 are rewritten) that are a conflict with another writer, and which conflict. */
    private static final Map<StoreCode, ConflictCode> STORE_CONFLICTS = Map.of(StoreCode.STORE_CONFLICT, ConflictCode.CONCURRENT_UPDATE,
            StoreCode.STORE_BUSY, ConflictCode.CONCURRENT_UPDATE, StoreCode.STORE_UNIQUE_VIOLATION, ConflictCode.DUPLICATE);

    private PersistenceErrors() {
    }

    /** The failure as Sennard states it, or the failure itself when it is none of the translated kinds. */
    public static RuntimeException translate(RuntimeException failure) {
        return switch (failure) {
            case SennardException own -> own;
            case ConstraintViolationException violations -> new InvalidInputException(InputCode.VALIDATION_FAILED, Map.of(), Map.of("violations", describe(violations)));
            case DataAccessException data -> ofDataAccess(data);
            case SettingsException settings -> ofSettings(settings);
            default -> failure;
        };
    }

    /**
     * What a data access exception is as Sennard states it, once, for both entrances: the application layer ({@link #translate}, run around every service method) and the HTTP boundary
     * ({@code SennardExceptionHandler}, for what a request meets outside a service method). A race with another writer is the retryable conflict, whichever code the database raised it
     * with; only a failure that is none of the translated kinds is an unavailable store. The old store exception ({@code StoreException} is a {@code DataAccessException}) is read first.
     */
    public static SennardException ofDataAccess(DataAccessException failure) {
        return switch (failure) {
            case StoreException store -> fromStore(store);
            case DuplicateKeyException duplicate -> new ConflictException(ConflictCode.DUPLICATE, Map.of(), duplicate);
            case ConcurrencyFailureException race -> new ConflictException(ConflictCode.CONCURRENT_UPDATE, Map.of(), race);
            case DataIntegrityViolationException integrity -> new IntegrityException(isForeignKey(integrity) ? IntegrityCode.REFERENCE_VIOLATION : IntegrityCode.VALUE_NOT_ALLOWED, Map.of(), integrity);
            default -> unavailable("STORE_FAILURE", failure);
        };
    }

    /**
     * What the settings store (the passkey registry, the records before the link) refuses, as Sennard states it. The store throws its own {@link SettingsException} with a fixed code, and
     * the entrance that answers a request ({@code SennardExceptionHandler}, {@link #translate}) states it here, once, with no default: a code added to {@link SettingsCode} does not compile
     * until it has its answer. (A write that meets another caller's uncommitted one is not a settings refusal: the database raises it as a {@code ConcurrencyFailureException}, which
     * {@link #ofDataAccess} answers with the retryable conflict every race is answered with.)
     */
    public static SennardException ofSettings(SettingsException settings) {
        Map<String, Object> reason = SennardException.args("reason", settings.code().name());
        return switch (settings.code()) {
            case INVALID_SETTING, VALUE_OUTSIDE_SET -> new InvalidInputException(InputCode.VALIDATION_FAILED, Map.of(), reason, settings);
            case STORED_VALUE_UNKNOWN -> new IntegrityException(IntegrityCode.VALUE_NOT_ALLOWED, Map.of(), settings);
            case PASSKEY_NOT_FOUND -> new IntegrityException(IntegrityCode.REFERENCE_VIOLATION, Map.of(), settings);
            case PASSKEY_EXISTS, REQUEST_ALREADY_RECORDED -> new ConflictException(ConflictCode.DUPLICATE, Map.of(), reason, settings);
            case PRE_LINK_CLOSED -> new InvalidInputException(InputCode.ADMIN_CHANGE_INVALID, Map.of(), SennardException.args("reason", "DATABASE_LINK_CLOSED"), settings);
            case SETTINGS_UNAVAILABLE, MIGRATION_FAILED, MIGRATION_CHANGED, MIGRATION_MISSING, MIGRATION_OUT_OF_ORDER, MIGRATION_GAP -> unavailable(settings.code().name(), settings);
        };
    }

    private static SennardException fromStore(StoreException store) {
        if (store.code() == StoreCode.BUSINESS_DATABASE_NOT_H2) {
            return new InvalidInputException(InputCode.BUSINESS_DATABASE_NOT_H2, Map.of(), Map.of(), store);
        }
        ConflictCode conflict = STORE_CONFLICTS.get(store.code());
        return conflict != null ? new ConflictException(conflict, Map.of(), store) : unavailable(store.code().name(), store);
    }

    private static UnavailableException unavailable(String reason, RuntimeException cause) {
        Map<String, Object> facts = SennardException.args("reason", reason, "cause", cause.getClass().getSimpleName());
        return new UnavailableException(UnavailableCode.STORE_UNAVAILABLE, facts, facts, cause);
    }

    private static boolean isForeignKey(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                    && (FOREIGN_KEY_VIOLATION.equals(sql.getSQLState()) || INTEGRITY_CLASS.equals(sql.getSQLState()) && MYSQL_FAMILY_FOREIGN_KEY_CODES.contains(sql.getErrorCode()))) {
                return true;
            }
        }
        return false;
    }

    private static List<Map<String, String>> describe(ConstraintViolationException violations) {
        List<Map<String, String>> described = new ArrayList<>();
        for (ConstraintViolation<?> violation : violations.getConstraintViolations()) {
            described.add(Map.of("field", violation.getPropertyPath().toString(), "message", violation.getMessage()));
        }
        return described;
    }
}
