// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.service.store.SqlMerge;

/**
 * The text and the parameters of the upsert that replaces {@code ON CONFLICT} and {@code MERGE}: a count of the key, an {@code UPDATE} and an {@code INSERT}, every one a statement
 * that PostgreSQL and MariaDB both have, with the placeholders in the order of the text. What they do on a real database is the stores' tests (ModuleStoresTest and the others).
 */
class SqlMergeTest {
    @Test
    void anUpsertCountsTheKeyThenUpdatesOrInserts() {
        SqlMerge merge = SqlMerge.into("sennard_x.t").key("a", "k1").key("b", 2L).set("c", "v").set("d", null);
        assertEquals("SELECT COUNT(*) FROM sennard_x.t WHERE a = ? AND b = ?", merge.existsSql());
        assertEquals("UPDATE sennard_x.t SET c = ?, d = ? WHERE a = ? AND b = ?", merge.updateSql());
        assertArrayEquals(new Object[] {"v", null, "k1", 2L}, merge.updateParams(), "the values, then the key");
        assertEquals("INSERT INTO sennard_x.t (a, b, c, d) VALUES (?, ?, ?, ?)", merge.insertSql());
        assertArrayEquals(new Object[] {"k1", 2L, "v", null}, merge.insertParams(), "the key, then the values");
    }

    @Test
    void columnsOnlyForTheInsertAreLeftOutOfTheUpdate() {
        SqlMerge keeping = SqlMerge.into("sennard_x.t").key("a", "k1").set("c", "v").insertOnly("created", "first");
        assertEquals("UPDATE sennard_x.t SET c = ? WHERE a = ?", keeping.updateSql());
        assertFalse(keeping.updateSql().contains("created"));
        assertEquals("INSERT INTO sennard_x.t (a, c, created) VALUES (?, ?, ?)", keeping.insertSql());
        assertArrayEquals(new Object[] {"k1", "v", "first"}, keeping.insertParams(), "an insert-only value is a parameter of the insert alone");
    }

    @Test
    void theStatementsUseNoSyntaxOfOneDbms() {
        SqlMerge merge = SqlMerge.into("sennard_x.t").key("a", "k1").set("c", "v").insertOnly("created", "first");
        for (String sql : new String[] {merge.existsSql(), merge.updateSql(), merge.insertSql()}) {
            assertFalse(sql.contains("MERGE") || sql.contains("CONFLICT") || sql.contains("DUPLICATE") || sql.contains("RETURNING"), sql);
        }
    }
}
