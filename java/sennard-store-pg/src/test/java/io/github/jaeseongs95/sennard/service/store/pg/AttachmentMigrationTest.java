// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import io.github.jaeseongs95.sennard.service.migration.MigrationScript;

/**
 * The attachment tables as V21 makes them: a bundle of files (attachment, attachment_file, attachment_storage, and upload_staging with attachment_id and seq). Cases (written first):
 * <pre>
 * a2  constraints            a second upload row under the same (owner, operation_key) takes seq 1 and refuses seq 0; the same (attachment_id, seq) twice is refused; a storage_key twice in
 *                            attachment_storage is refused, so is (attachment_id, seq, rev) twice, and a negative seq or rev (in the storage, the files and the uploads)
 * a3  rerun                  the runner applies nothing when the V22 prefix, and then every current script, is already applied
 * a4  empty database         all current expand scripts on an empty database: the three attachment tables have the intended columns (name and type, in order) and primary keys, upload_staging has attachment_id and seq
 *                            (NOT NULL), there is no foreign key, and module_schema attachment is 1
 * </pre>
 */
@BusinessDbTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AttachmentMigrationTest {
    private static final String A = "aaaaaaaa-0000-0000-0000-00000000000a";
    private static final String B = "bbbbbbbb-0000-0000-0000-00000000000b";
    private static final String U1 = "11111111-0000-0000-0000-000000000001";
    private static final String U2 = "22222222-0000-0000-0000-000000000002";
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final String KEY_A0 = "a0".repeat(16);
    private static final String KEY_A1 = "a1".repeat(16);
    private static final String KEY_B0 = "b0".repeat(16);
    private static final String KEY_U1 = "c1".repeat(16);
    private static final String KEY_U2 = "c2".repeat(16);

    private final SennardDatabaseConfig config = BusinessDb.config();
    private MigrationRunner runner;
    private final List<MigrationScript> all = MigrationScript.load("db/expand");

    @BeforeAll
    void start() {
        runner = new MigrationRunner(Clock.systemUTC(), BusinessDb.dataSources());
    }

    @AfterAll
    void leaveTheDatabaseMigrated() throws SQLException {
        // the classes after this one start from the schema the class annotation migrated, so what these cases dropped is made again
        BusinessDb.dropBusinessSchemas();
        runner.run(config, MigrationRunner.Phase.EXPAND, all);
    }

    // ------------------------------------------------------------------------------------------------------------------ fixtures

    private List<MigrationScript> upTo(int version) {
        return all.stream().filter(script -> script.version() <= version).toList();
    }

    private void execute(String... statements) throws SQLException {
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement()) {
            for (String statement : statements) {
                s.execute(statement);
            }
        }
    }

    private long count(String sql) throws SQLException {
        return Long.parseLong(rows(sql).get(0).get(0));
    }

    /** Every row of {@code sql} with every column read as text, so a table is compared column by column without a type of its own. */
    private List<List<String>> rows(String sql) throws SQLException {
        List<List<String>> result = new ArrayList<>();
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement(); ResultSet set = s.executeQuery(sql)) {
            ResultSetMetaData meta = set.getMetaData();
            while (set.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.add(set.getString(i));
                }
                result.add(row);
            }
        }
        return result;
    }

    private static String upload(String id, String owner, String operationKey, String storageKey, String state, String extraColumns, String extraValues) {
        return "INSERT INTO sennard.upload_staging (id, owner, operation_key, file_name, media_type, declared_size, declared_sha256, received_bytes, stored_bytes, next_sequence, last_chunk_sha256, "
                + "storage_key, key_id, state, expires_at, created_at, updated_at" + extraColumns + ") VALUES ('" + id + "', '" + owner + "', '" + operationKey + "', 'up.txt', 'text/plain', 10, '" + SHA_A
                + "', 10, 12, 3, '" + SHA_B + "', '" + storageKey + "', 'k-1', '" + state
                + "', TIMESTAMP '2026-03-01 00:00:00', TIMESTAMP '2026-02-01 10:00:00', TIMESTAMP '2026-02-01 10:05:00'" + extraValues + ")";
    }

    private void migrateEmptyTo(int version) throws SQLException {
        BusinessDb.dropBusinessSchemas();
        assertEquals(version, runner.run(config, MigrationRunner.Phase.EXPAND, upTo(version)), "scripts V1 to V" + version);
    }

    private void migrateEmptyAll() throws SQLException {
        BusinessDb.dropBusinessSchemas();
        assertEquals(all.size(), runner.run(config, MigrationRunner.Phase.EXPAND, all), "all current expand scripts");
    }

    // ---------------------------------------------------------------------------------------------------------------------- cases

    @Test
    void a2_theConstraintsWork() throws Exception {
        migrateEmptyTo(22);
        String bundle = "dddddddd-0000-0000-0000-00000000000d";
        String other = "eeeeeeee-0000-0000-0000-00000000000e";
        String more = ", attachment_id, seq";
        execute(upload(U1, "codex:s1", "op-1", KEY_U1, "STAGING", more, ", '" + bundle + "', 0"));
        execute(upload(U2, "codex:s1", "op-1", KEY_U2, "STAGING", more, ", '" + bundle + "', 1"));
        assertEquals(2, count("SELECT count(*) FROM sennard.upload_staging WHERE owner = 'codex:s1' AND operation_key = 'op-1'"), "one begin, two files, one operation key");
        assertThrows(SQLException.class, () -> execute(upload("33333333-0000-0000-0000-000000000003", "codex:s1", "op-1", "c3".repeat(16), "STAGING", more, ", '" + other + "', 0")),
                "(owner, operation_key, seq) twice");
        assertThrows(SQLException.class, () -> execute(upload("44444444-0000-0000-0000-000000000004", "codex:s1", "op-2", "c4".repeat(16), "STAGING", more, ", '" + bundle + "', 1")),
                "(attachment_id, seq) twice");
        assertThrows(SQLException.class, () -> execute(upload("55555555-0000-0000-0000-000000000005", "codex:s1", "op-3", "c5".repeat(16), "STAGING", more, ", '" + other + "', -1")),
                "a negative seq of an upload");
        assertEquals(2, count("SELECT count(*) FROM sennard.upload_staging"), "the refused rows are not there");

        execute(storage(A, 0, 0, KEY_A0));
        assertThrows(SQLException.class, () -> execute(storage(B, 0, 0, KEY_A0)), "a storage_key twice");
        assertThrows(SQLException.class, () -> execute(storage(A, 0, 0, KEY_A1)), "(attachment_id, seq, rev) twice");
        assertThrows(SQLException.class, () -> execute(storage(A, -1, 0, KEY_A1)), "a negative seq in the storage");
        assertThrows(SQLException.class, () -> execute(storage(A, 0, -1, KEY_A1)), "a negative rev");
        execute(storage(A, 0, 1, KEY_A1), storage(A, 1, 0, KEY_B0));
        assertEquals(3, count("SELECT count(*) FROM sennard.attachment_storage"), "a next rev and a second file are what the table is for");

        execute(file(A, 0, 5));
        assertThrows(SQLException.class, () -> execute(file(A, 0, 5)), "(attachment_id, seq) twice in the files");
        assertThrows(SQLException.class, () -> execute(file(A, -1, 5)), "a negative seq of a file");
        assertThrows(SQLException.class, () -> execute(file(A, 1, -5)), "a negative size");
    }

    @Test
    void a3_aRerunAppliesNothing() throws Exception {
        migrateEmptyTo(22);
        assertEquals(0, runner.run(config, MigrationRunner.Phase.EXPAND, upTo(22)));
        migrateEmptyAll();
        assertEquals(0, runner.run(config, MigrationRunner.Phase.EXPAND, all), "every script is applied");
        assertEquals(1, count("SELECT count(*) FROM sennard.expand_history WHERE version = '21' AND outcome = 'SUCCEEDED'"));
    }

    @Test
    void a4_anEmptyDatabaseHasTheIntendedShape() throws Exception {
        migrateEmptyAll();
        assertEquals(all.size(), count("SELECT count(*) FROM sennard.expand_history"), "every script is in the history");
        assertEquals(List.of("id:character", "created_by:character varying", "created_at:timestamp without time zone"), columns("attachment"));
        assertEquals(List.of("attachment_id:character", "seq:integer", "file_name:character varying", "media_type:character varying", "size_bytes:bigint", "content_sha256:character"),
                columns("attachment_file"));
        assertEquals(List.of("attachment_id:character", "seq:integer", "rev:integer", "storage_key:character", "key_id:character varying", "created_at:timestamp without time zone"),
                columns("attachment_storage"));
        assertEquals(List.of("id"), primaryKey("attachment"));
        assertEquals(List.of("attachment_id", "seq"), primaryKey("attachment_file"));
        assertEquals(List.of("attachment_id", "seq", "rev"), primaryKey("attachment_storage"));
        assertEquals(List.of("id"), primaryKey("upload_staging"));
        List<String> upload = columns("upload_staging");
        assertTrue(upload.contains("attachment_id:character"), upload.toString());
        assertTrue(upload.contains("seq:integer"), upload.toString());
        assertEquals(0, count("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'sennard' AND table_name = 'upload_staging' AND column_name IN ('attachment_id', 'seq') AND is_nullable <> 'NO'"),
                "both new columns are NOT NULL");
        assertEquals(0, count("SELECT count(*) FROM information_schema.table_constraints WHERE table_schema = 'sennard' AND constraint_type = 'FOREIGN KEY' AND table_name IN "
                + "('attachment', 'attachment_file', 'attachment_storage', 'upload_staging')"), "the attachment tables have no foreign key");
        assertEquals(1, count("SELECT version FROM sennard.module_schema WHERE module = 'attachment'"));
    }

    private List<String> columns(String table) throws SQLException {
        return rows("SELECT column_name || ':' || data_type FROM information_schema.columns WHERE table_schema = 'sennard' AND table_name = '" + table + "' ORDER BY ordinal_position").stream()
                .map(row -> row.get(0)).toList();
    }

    private List<String> primaryKey(String table) throws SQLException {
        return rows("SELECT k.column_name FROM information_schema.table_constraints c JOIN information_schema.key_column_usage k ON k.constraint_schema = c.constraint_schema "
                + "AND k.constraint_name = c.constraint_name AND k.table_schema = c.table_schema AND k.table_name = c.table_name "
                + "WHERE c.constraint_type = 'PRIMARY KEY' AND c.table_schema = 'sennard' AND c.table_name = '" + table + "' ORDER BY k.ordinal_position").stream().map(row -> row.get(0)).toList();
    }

    private static String storage(String attachmentId, int seq, int rev, String storageKey) {
        return "INSERT INTO sennard.attachment_storage (attachment_id, seq, rev, storage_key, key_id, created_at) VALUES ('" + attachmentId + "', " + seq + ", " + rev + ", '" + storageKey
                + "', 'k-1', TIMESTAMP '2026-02-01 08:00:00')";
    }

    private static String file(String attachmentId, int seq, long size) {
        return "INSERT INTO sennard.attachment_file (attachment_id, seq, file_name, media_type, size_bytes, content_sha256) VALUES ('" + attachmentId + "', " + seq + ", 'f.txt', 'text/plain', " + size
                + ", '" + SHA_A + "')";
    }
}
