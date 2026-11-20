// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.migration;

/**
 * A run of {@link HistoryMigrator} that stopped, with a {@link MigrationCode}. The message names a script file and its location, never SQL text or a value; the adapter of each
 * product turns it into the failure type of that product.
 */
public final class MigrationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final MigrationCode code;

    public MigrationException(MigrationCode code, String message) {
        super(message);
        this.code = code;
    }

    public MigrationException(MigrationCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public MigrationCode code() {
        return code;
    }
}
