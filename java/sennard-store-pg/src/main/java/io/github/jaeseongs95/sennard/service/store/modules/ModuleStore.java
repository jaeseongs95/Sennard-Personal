// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.modules;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.util.ClassUtils;

import io.github.jaeseongs95.sennard.domain.Timestamps;
import io.github.jaeseongs95.sennard.service.store.SqlMerge;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import tools.jackson.databind.json.JsonMapper;

/**
 * What every module repository shares: the statement helpers. A repository method joins the transaction it is
 * called in; the service method that composes several opens it with {@code @Transactional} and then calls them. No lock of the module is taken by hand: a race over one row is
 * decided by the {@code WHERE} of the one statement that writes it, a race over a new row by its key, and the rest by the transaction and its isolation level.
 *
 * <p>Rows that carry an encrypted value ({@link io.github.jaeseongs95.sennard.service.store.Encrypted} on a component of the row record) go through {@link #list}, {@link #single} (reading: the
 * values are opened) and {@link #upsertRow} (writing: the values are sealed), the one common path, so a repository does not seal by hand and a service does not know the sealer. Such a value
 * cannot be searched by equality or ordered in SQL. The records nested in a store are checked when the store starts.
 *
 * <p>No method here is final: a repository is a CGLIB proxy, and a final method would run on the empty proxy instance.
 */
public abstract class ModuleStore implements InitializingBean {
    protected final JdbcClient jdbc;
    private EncryptedRecords encrypted;

    protected ModuleStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Autowired
    void encryptedRecords(EncryptedRecords encrypted) {
        this.encrypted = encrypted;
    }

    /** A record nested in the store whose {@code @Encrypted} declaration is wrong stops the start, not a later row (a value would otherwise be stored unsealed or not at all). */
    @Override
    public void afterPropertiesSet() {
        for (Class<?> member : ClassUtils.getUserClass(getClass()).getNestMembers()) {
            encrypted.check(member);
        }
    }

    /** The rows of a query, mapped to the record by component name; the encrypted components are opened. */
    protected <T> List<T> list(Class<T> type, String sql, Object... params) {
        List<T> rows = new ArrayList<>();
        for (T row : jdbc.sql(sql).params(StoreValues.bind(params)).query(type).list()) {
            rows.add(encrypted.open(row));
        }
        return rows;
    }

    /** {@link #list} for at most one row: the first, or null when there is none. */
    protected <T> T single(Class<T> type, String sql, Object... params) {
        return firstOrNull(list(type, sql, params));
    }

    /**
     * The row record is written like {@link #upsert}: every component is a column (its name in snake case, or the one the {@code @Encrypted} declaration names), the columns named
     * in {@code keyColumns} are the key the row is found by, and the encrypted components are sealed. A {@code null} component is written as {@code null}.
     */
    protected int upsertRow(String table, Object row, String... keyColumns) {
        Map<String, Object> columns = encrypted.columns(row);
        SqlMerge merge = SqlMerge.into(table);
        for (String key : keyColumns) {
            if (!columns.containsKey(key)) {
                throw new IllegalArgumentException("The key column " + key + " is not a column of " + row.getClass().getName() + ".");
            }
            merge.key(key, columns.get(key));
        }
        for (Map.Entry<String, Object> column : columns.entrySet()) {
            if (!List.of(keyColumns).contains(column.getKey())) {
                merge.set(column.getKey(), column.getValue());
            }
        }
        return upsert(merge);
    }

    /** The first row of a result, or null when there is none (a result that may be empty is a list, not an Optional of a field). */
    protected static <T> T firstOrNull(List<T> rows) {
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Runs a statement and returns the changed rows. */
    protected int update(String sql, Object... params) {
        return jdbc.sql(sql).params(StoreValues.bind(params)).update();
    }

    /**
     * Inserts a row whose key the database makes ({@code GENERATED ... AS IDENTITY}) and returns that key, with the JDBC generated-keys API (the statement has no {@code RETURNING},
     * which is not standard SQL). The key is of the row this transaction just wrote.
     */
    protected long insertReturningId(String sql, String keyColumn, Object... params) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql(sql).params(StoreValues.bind(params)).update(keys, keyColumn);
        Number key = keys.getKey();
        if (key == null) {
            throw new StoreException(StoreCode.STORE_FAILURE, "The database returned no generated key for " + keyColumn + ".");
        }
        return key.longValue();
    }

    /**
     * The row is written: updated when its key exists, inserted when it does not, in the transaction of the service method that calls this. Two first writes of one key at the same
     * moment end the second in a unique violation (STORE_UNIQUE_VIOLATION), which is not caught or run again ({@link SqlMerge}).
     */
    protected int upsert(SqlMerge merge) {
        return merge.upsert(jdbc, params -> StoreValues.bind(params));
    }

    /** The row is inserted when its key does not exist; the changed rows are 1 for the caller that wrote it and 0 when it was there. */
    protected int insertIfAbsent(SqlMerge merge) {
        return merge.insertIfAbsent(jdbc, params -> StoreValues.bind(params));
    }

    /**
     * Copies matching rows into a module's backup table ({@code continuity_cleanup_backup}, {@code workflow_cleanup_backup}), in the transaction of the deletion (DBMS 3.2). Table names are the
     * constants of the calling store. {@code next} is the row number to use next per table; the caller keeps it per plan. Each row is one JSON object of its columns, made here by Jackson
     * (a time as the fixed-width UTC text of {@link Timestamps}); the database makes no JSON.
     */
    protected long backup(JsonMapper mapper, String backupTable, String planId, String createdAt, String table, String where, long next, Object... params) {
        List<Map<String, Object>> rows = jdbc.sql("SELECT * FROM " + table + " t WHERE " + where).params(StoreValues.bind(params)).query(ModuleStore::columns).list();
        String tableName = table.substring(table.indexOf('.') + 1);
        long number = next;
        for (Map<String, Object> row : rows) {
            number++;
            update("INSERT INTO " + backupTable + " (plan_id, table_name, row_no, row_json, created_at) VALUES (?, ?, ?, ?, ?)",
                    planId, tableName, number, mapper.writeValueAsString(row), StoreValues.ts(createdAt));
        }
        return number;
    }

    /** One row as its columns in the table's order; a TIMESTAMP is read as the stored UTC fields and written as text, never through the JVM's zone. */
    private static Map<String, Object> columns(ResultSet row, int rowNumber) throws SQLException {
        ResultSetMetaData columns = row.getMetaData();
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 1; index <= columns.getColumnCount(); index++) {
            Object value = columns.getColumnType(index) == Types.TIMESTAMP ? row.getObject(index, LocalDateTime.class) : row.getObject(index);
            values.put(columns.getColumnLabel(index), value instanceof LocalDateTime time ? Timestamps.format(time) : value);
        }
        return values;
    }
}
