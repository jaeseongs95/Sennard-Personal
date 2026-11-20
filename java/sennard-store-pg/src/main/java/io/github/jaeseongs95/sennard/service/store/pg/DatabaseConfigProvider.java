// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

/**
 * Where the connection settings of the business database come from. The database is a service the user runs, so a provider only says how to reach it; the
 * preparation (migrations, the pool) is {@link PreparedStore}'s and is the same for every provider. The settings the user gives are the one real implementation
 * ({@link RemoteConfigProvider}); the tests make their own.
 */
public interface DatabaseConfigProvider {
    /** Returns how to reach the database. Failures are StoreException with a fixed code. */
    SennardDatabaseConfig provide();

    /** Ends what {@link #provide()} started, if this provider started anything. The caller still holds the service owner lock. */
    default void stop() {
    }
}
