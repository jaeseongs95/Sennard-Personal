// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import com.zaxxer.hikari.HikariDataSource;

import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import io.github.jaeseongs95.sennard.service.store.StoreException.Effect;

/**
 * The business DataSource before and after the store is ready. The server listens first and prepares the database
 * behind it: until then every connection request is answered at once with STORE_PREPARING and the phase, and no code above this
 * class knows the store is being prepared.
 */
public final class PreparingDataSource extends DelegatingDataSource implements AutoCloseable {
    /** The pool that the work of {@link #whileStarting} on this thread may use before the store is ready. */
    private static final ScopedValue<DataSource> STARTING = ScopedValue.newInstance();

    private final Supplier<Map<String, Object>> status;
    private HikariDataSource pool;
    private boolean closed;
    private volatile DataSource ready;

    /** @param status what the answer says while not ready: phase, startedAt, retryAfterMs */
    public PreparingDataSource(Supplier<Map<String, Object>> status) {
        this.status = status;
    }

    /**
     * Runs the work that must be finished before the store is ready (the keys and secrets a request only reads, the recovery of the jobs of the last run) on the pool that passed the
     * start-up checks. The pool is given to the calling thread's work only, for as long as the work runs: any other thread, a request, is still answered with STORE_PREPARING until
     * {@link #ready}. A failure of the work leaves the store as it was and reaches the caller, which decides about the pool.
     */
    public void whileStarting(DataSource target, Runnable work) {
        ScopedValue.where(STARTING, target).run(work);
    }

    /** Takes the pool once it passed the start-up checks; from here on nothing is STORE_PREPARING. After {@link #close()} it just closes it. */
    public synchronized void ready(HikariDataSource pool) {
        if (closed) {
            pool.close();
            return;
        }
        this.pool = pool;
        setTargetDataSource(pool);
        ready = pool;
    }

    /** Closes the pool. A connection request after this is refused like one before {@link #ready}. */
    @Override
    public synchronized void close() {
        closed = true;
        ready = null;
        if (pool != null) {
            pool.close();
        }
    }

    @Override
    public void afterPropertiesSet() {
        // The target arrives when the preparation ends.
    }

    @Override
    public Connection getConnection() throws SQLException {
        return readyTarget().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return readyTarget().getConnection(username, password);
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        return readyTarget().unwrap(type);
    }

    private DataSource readyTarget() {
        DataSource target = ready;
        if (target != null) {
            return target;
        }
        if (STARTING.isBound()) {
            return STARTING.get();
        }
        throw new StoreException(StoreCode.STORE_PREPARING, null, Effect.NONE, "The database is not ready.", null, status.get());
    }
}
