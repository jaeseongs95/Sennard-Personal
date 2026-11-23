// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * Rule k of the machine checks (docs/sennard-architecture-checks.md): a column that refers to another table's key must have a foreign key. The
 * database declares what it keeps true, so a reference with no foreign key is a fact nothing guards. The check reads the schema the migrations
 * build on the database the test run names ({@link BusinessDb}: PostgreSQL; only its schema {@code sennard}), through {@code information_schema} (the standard catalog), and counts a column as a reference when
 *
 * <ul>
 * <li>its name ends in {@code _id} or {@code _ref},</li>
 * <li>another table has a primary key column of that name and data type, and</li>
 * <li>no foreign key of its own table covers it.</li>
 * </ul>
 * A name match with no reference relationship is excluded only if it is listed in {@link #NON_REFERENCES} and still meets those criteria.
 *
 * What it misses: a reference whose column is named differently from the key it points at ({@code sender_ref} for {@code session_ref}), and a
 * reference to a key whose name does not end in {@code _id} or {@code _ref}. The ceiling is the number of such columns now; the 6.0.0 schema
 * brings it to 0. Like the other ceilings it fails above (a new reference without a key) and below (lower the number).
 */
@BusinessDbTest
class ForeignKeyCatalogTest {
    /**
     * The columns the rule lists in the schema of {@code db/expand} now: 18, counted by hand from the scripts and not measured on a database (this class has not run on the
     * final scripts yet). Each one is a column that names a key of another table and has no foreign key, grouped by the column name:
     * <ul>
     * <li>{@code session_id} (5): sennard.board_sessions, sennard.session_seen (these two take each other's key for the referenced one), sennard.skill_calls,
     * sennard.input_source_receipts, sennard.admin_proposals. The text type decides: sennard.user_prompt.session_id is VARCHAR and is not listed.</li>
     * <li>{@code plan_id} (3): sennard.continuity_cleanup_backup, sennard.workflow_cleanup_backup, sennard.state_cleanup_claims (each is a primary key column of the other two).</li>
     * <li>{@code root_id} (3): sennard.convergence_roots and sennard.convergence_root_identities (their own key; the rule takes the other convergence tables that
     * have {@code root_id} in their primary key for the referenced one) and sennard.continuity_tasks.</li>
     * <li>{@code call_id} (1): sennard.skill_calls (the key of sennard.call_admission).</li>
     * <li>{@code role_id} (1): sennard.role_context_history (the key of sennard.role_contexts).</li>
     * <li>{@code run_id} (1): sennard.workflow_runs (the key of sennard.workflow_attempt_links).</li>
     * <li>{@code message_id} (1): sennard.prepared_messages (the key of sennard.messages, which has the foreign key to the draft).</li>
     * <li>{@code attachment_id} (3): sennard.attachment_file and sennard.attachment_storage (each is a primary key column of the other; an attachment has no foreign key by design: the three tables are inserted and never changed) and sennard.upload_staging (the key of both).</li>
     * </ul>
     * skill_calls.skill_id matches workflow_run_skills.skill_id by name only (V22); skills are files, not rows, so there is no key to refer to.
     */
    private static final int CEILING = 19;
    private static final Map<String, String> NON_REFERENCES = Map.of(
            "sennard.convergence_roots.workspace_id",
            "The root's workspace identifier does not point to a blocked plan. The plan's workspace key is a hash of the physical path.",
            "sennard.workflow_blocked_plans.run_id",
            "The run ID records the origin of the block. Removing a run must leave the block in effect until an administrator unblocks it.");

    private record Column(String schema, String table, String name, String type) {
        String qualified() {
            return schema + "." + table;
        }

        @Override
        public String toString() {
            return qualified() + "." + name + " (" + type + ")";
        }
    }

    @Test
    void everyReferenceColumnHasAForeignKey() throws Exception {
        try (Connection owner = BusinessDb.connect()) { // the schema was migrated at the start of the class; only the schema sennard is read
            check(owner);
        }
    }

    private static void check(Connection connection) throws SQLException {
        List<Column> columns = new ArrayList<>();
        Map<String, Set<String>> primaryKeys = new HashMap<>();
        Set<String> foreignKeyColumns = new HashSet<>();
        try (Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("SELECT table_schema, table_name, column_name, data_type FROM information_schema.columns "
                    + "WHERE table_schema = 'sennard' ORDER BY table_schema, table_name, ordinal_position")) {
                while (rows.next()) {
                    columns.add(new Column(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4)));
                }
            }
            for (String kind : List.of("PRIMARY KEY", "FOREIGN KEY")) {
                try (ResultSet rows = statement.executeQuery("SELECT k.table_schema, k.table_name, k.column_name FROM information_schema.table_constraints c "
                        // The table is part of the join: a constraint name is unique only within its table, so the constraint's schema and name alone could match the keys of other tables.
                        + "JOIN information_schema.key_column_usage k ON k.constraint_schema = c.constraint_schema AND k.constraint_name = c.constraint_name "
                        + "AND k.table_schema = c.table_schema AND k.table_name = c.table_name "
                        + "WHERE c.constraint_type = '" + kind + "' AND k.table_schema = 'sennard'")) {
                    while (rows.next()) {
                        String column = rows.getString(1) + "." + rows.getString(2) + "." + rows.getString(3);
                        if (kind.equals("PRIMARY KEY")) {
                            primaryKeys.computeIfAbsent(rows.getString(3), key -> new TreeSet<>()).add(rows.getString(1) + "." + rows.getString(2));
                        } else {
                            foreignKeyColumns.add(column);
                        }
                    }
                }
            }
        }
        Map<String, String> typeOfKey = new HashMap<>();
        for (Column column : columns) {
            if (primaryKeys.getOrDefault(column.name(), Set.of()).contains(column.qualified())) {
                typeOfKey.put(column.qualified() + "." + column.name(), column.type());
            }
        }
        Map<String, String> missing = new TreeMap<>();
        Map<String, String> unmatchedExceptions = new TreeMap<>(NON_REFERENCES);
        for (Column column : columns) {
            boolean looksLikeReference = column.name().endsWith("_id") || column.name().endsWith("_ref");
            if (!looksLikeReference || foreignKeyColumns.contains(column.qualified() + "." + column.name())) {
                continue;
            }
            String stem = column.name().substring(0, column.name().lastIndexOf('_'));
            if (column.table().equals(column.name()) || column.table().equals(stem) || column.table().equals(stem + "s")) {
                continue; // the table that this key names (user_prompt.user_prompt_id, messages.message_id) is where the key lives, not a reference to it
            }
            String found = null;
            for (String owner : primaryKeys.getOrDefault(column.name(), Set.of())) {
                if (!owner.equals(column.qualified()) && column.type().equals(typeOfKey.get(owner + "." + column.name()))) {
                    String ownerTable = owner.substring(owner.indexOf('.') + 1);
                    if (ownerTable.equals(stem) || ownerTable.equals(stem + "s")) {
                        found = owner; // the table the key is named after is the one a foreign key should point at
                        break;
                    }
                    found = found == null ? owner : found;
                }
            }
            if (found != null && unmatchedExceptions.remove(column.qualified() + "." + column.name()) == null) {
                missing.put(column.toString(), "refers by name to the key " + found + "." + column.name());
            }
        }
        assertEquals(Map.of(), unmatchedExceptions, "Each non-reference exception must still match a candidate without a foreign key");
        List<String> places = missing.entrySet().stream().map(entry -> "    " + entry.getKey() + "  " + entry.getValue()).toList();
        if (missing.size() > CEILING) {
            fail("k-missing-foreign-key: " + missing.size() + " reference columns without a foreign key, the ceiling is " + CEILING + ":\n" + String.join("\n", places));
        }
        assertEquals(CEILING, missing.size(), "k-missing-foreign-key: below the ceiling; lower CEILING in ForeignKeyCatalogTest to " + missing.size() + ":\n" + String.join("\n", places));
    }
}
