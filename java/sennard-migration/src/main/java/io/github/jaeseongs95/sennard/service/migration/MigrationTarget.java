// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.migration;

/**
 * Where a run keeps its history and what it calls the scripts' place in its messages.
 *
 * @param schema the schema of the history table, written in lower case (the runner makes it when it is not there)
 * @param table the history table, written in lower case
 * @param location the folder of the scripts in the jar, for the messages ({@code db/expand})
 */
public record MigrationTarget(String schema, String table, String location) {
    /** The history table qualified by its schema. */
    public String qualified() {
        return schema + "." + table;
    }
}
