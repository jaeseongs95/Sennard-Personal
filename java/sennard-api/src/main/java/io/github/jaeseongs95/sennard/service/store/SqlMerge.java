// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * An upsert in statements every DBMS has: a {@code SELECT COUNT(*)} of the key, then an {@code UPDATE ... WHERE <key>} when the row is there and an {@code INSERT} when it is not.
 * It replaces the {@code ON CONFLICT} clause (PostgreSQL's alone) and the standard {@code MERGE} (which MariaDB and MySQL do not have). The statements and their parameters are built
 * together, so the order of the placeholders is never written by hand. Whether the row is there is asked with a count, not read from the changed-rows number of the {@code UPDATE}:
 * that number is "matched" for some drivers and "changed" for others (MariaDB's {@code useAffectedRows}), and an {@code UPDATE} that changes nothing must not be taken for a missing row.
 *
 * <p>It runs inside the transaction of the service method that calls it (a use case: the reading, the checks, the UPDATE and the INSERT are one transaction). It is not atomic against a
 * writer of the same key that has not committed: of two transactions that both find no row, the second one's INSERT meets the first one's key, and the primary key or UNIQUE
 * constraint refuses it with a unique violation, which the store reports as {@code STORE_UNIQUE_VIOLATION}. That call ends with that failure: nothing runs it again, and nothing
 * catches the duplicate to go on. Two first writes of the same key at the same moment are rare, and the database keeps one row, which is what matters; this is a limit that is accepted.
 */
public final class SqlMerge {
    private final String table;
    private final List<String> keyColumns = new ArrayList<>();
    private final List<Object> keyValues = new ArrayList<>();
    private final List<String> valueColumns = new ArrayList<>();
    private final List<Object> values = new ArrayList<>();
    private final List<String> insertColumns = new ArrayList<>();
    private final List<Object> insertValues = new ArrayList<>();

    private SqlMerge(String table) {
        this.table = table;
    }

    /** @param table the table, qualified by its schema */
    public static SqlMerge into(String table) {
        return new SqlMerge(table);
    }

    /** A column of the key the row is found by. */
    public SqlMerge key(String column, Object value) {
        keyColumns.add(column);
        keyValues.add(value);
        return this;
    }

    /** A column that is written, whether the row exists or not. */
    public SqlMerge set(String column, Object value) {
        valueColumns.add(column);
        values.add(value);
        return this;
    }

    /** A column that is written when the row is inserted and left as it is when the row exists. */
    public SqlMerge insertOnly(String column, Object value) {
        insertColumns.add(column);
        insertValues.add(value);
        return this;
    }

    /** The row is written: updated when its key exists, inserted when it does not; the changed rows (1 unless the row exists and there is nothing to update). */
    public int upsert(JdbcClient jdbc) {
        return upsert(jdbc, UnaryOperator.identity());
    }

    /** {@link #upsert(JdbcClient)} with the parameters made database values by {@code bind} (a store converts its enums and times there). */
    public int upsert(JdbcClient jdbc, UnaryOperator<Object[]> bind) {
        if (!exists(jdbc, bind)) {
            return jdbc.sql(insertSql()).params(bind.apply(insertParams())).update();
        }
        return valueColumns.isEmpty() ? 0 : jdbc.sql(updateSql()).params(bind.apply(updateParams())).update();
    }

    /** The row is inserted when its key does not exist; the changed rows are 1 for the caller that wrote it and 0 when it was there. */
    public int insertIfAbsent(JdbcClient jdbc) {
        return insertIfAbsent(jdbc, UnaryOperator.identity());
    }

    /** {@link #insertIfAbsent(JdbcClient)} with the parameters made database values by {@code bind}. */
    public int insertIfAbsent(JdbcClient jdbc, UnaryOperator<Object[]> bind) {
        return exists(jdbc, bind) ? 0 : jdbc.sql(insertSql()).params(bind.apply(insertParams())).update();
    }

    private boolean exists(JdbcClient jdbc, UnaryOperator<Object[]> bind) {
        return jdbc.sql(existsSql()).params(bind.apply(keyValues.toArray())).query(Long.class).single() > 0;
    }

    /** Counts the rows of the key (0 or 1). Its parameters are the key's values. */
    public String existsSql() {
        return "SELECT COUNT(*) FROM " + table + " WHERE " + keyCondition();
    }

    /** Sets the value columns of the row of the key. */
    public String updateSql() {
        return "UPDATE " + table + " SET " + String.join(", ", valueColumns.stream().map(column -> column + " = ?").toList()) + " WHERE " + keyCondition();
    }

    /** The parameters of {@link #updateSql()}: the values, then the key. */
    public Object[] updateParams() {
        List<Object> params = new ArrayList<>(values);
        params.addAll(keyValues);
        return params.toArray();
    }

    /** Writes the row: the key, the values, then the columns of the insert alone. */
    public String insertSql() {
        List<String> columns = new ArrayList<>(keyColumns);
        columns.addAll(valueColumns);
        columns.addAll(insertColumns);
        return "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES (" + String.join(", ", columns.stream().map(column -> "?").toList()) + ")";
    }

    /** The parameters of {@link #insertSql()}. */
    public Object[] insertParams() {
        List<Object> params = new ArrayList<>(keyValues);
        params.addAll(values);
        params.addAll(insertValues);
        return params.toArray();
    }

    private String keyCondition() {
        return String.join(" AND ", keyColumns.stream().map(column -> column + " = ?").toList());
    }
}
