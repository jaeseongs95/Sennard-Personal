// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store;

import java.util.Map;

import org.springframework.dao.DataAccessException;

/**
 * A store failure with a fixed code: what the {@code SQLExceptionTranslator} of the
 * store produces, so a store method lets Spring's exception path carry it. The message never carries SQL text, parameter values or
 * the server's own message. A caller never turns one of these into an empty result or an allow.
 */
public final class StoreException extends DataAccessException {
    private static final long serialVersionUID = 1L;

    /** NONE: nothing was written (rolled back or never started). UNKNOWN: the commit may or may not have happened. */
    public enum Effect { NONE, UNKNOWN }

    private final StoreCode code;
    private final String sqlState;
    private final Effect effect;
    /** What a {@code STORE_PREPARING} answer carries (phase, startedAt, retryAfterMs); empty otherwise. */
    private final transient Map<String, Object> details;

    public StoreException(StoreCode code, String sqlState, Effect effect, String message, Throwable cause) {
        this(code, sqlState, effect, message, cause, Map.of());
    }

    public StoreException(StoreCode code, String sqlState, Effect effect, String message, Throwable cause, Map<String, Object> details) {
        super(code + ": " + message, cause);
        this.code = code;
        this.sqlState = sqlState;
        this.effect = effect;
        this.details = Map.copyOf(details);
    }

    public StoreException(StoreCode code, String message) {
        this(code, null, Effect.NONE, message, null);
    }

    public StoreException(StoreCode code, String message, Throwable cause) {
        this(code, null, Effect.NONE, message, cause);
    }

    /** The Sennard reason, for example STORE_UNIQUE_VIOLATION, STORE_BUSY, MIGRATION_CHANGED. */
    public StoreCode code() {
        return code;
    }

    /** The SQLSTATE of the database's answer when the failure came from the server, else null. */
    public String sqlState() {
        return sqlState;
    }

    public Map<String, Object> details() {
        return details;
    }

    public Effect effect() {
        return effect;
    }
}
