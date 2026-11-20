// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.sql.Connection;
import java.sql.Statement;

import io.github.jaeseongs95.sennard.service.secret.TestPrivateStorage;

/**
 * The writer of {@link CsQueueReferenceTest}'s crash case: a JVM of its own that changes the queue's rows inside a transaction it never commits and then ends
 * at once ({@link Runtime#halt}, no shutdown hook, no closing of the connection), so that the database sees a client that vanished in the middle of a transaction.
 *
 * <p>A child process because a crash cannot be made by a thread of the test's own JVM. It is outside the test JVM, so it cannot borrow from the JUnit pool
 * ({@link BusinessDbExtension}): it opens its one connection the product's own way, a {@link DataSourceFactory} over a {@link JdbcDrivers} of its own that reads the user's
 * driver from the path of the same settings the pool has ({@link BusinessDb#config}; the parent passes them as system properties), and that connection is the one the account's
 * reserved connections allow for.
 */
public final class CsQueueCrashingWriter {
    /** The exit code the parent waits for, so that an ordinary failure of the child is not taken for the crash. */
    static final int CRASH_EXIT = 17;

    private CsQueueCrashingWriter() {
    }

    public static void main(String[] arguments) throws Exception {
        SennardDatabaseConfig config = BusinessDb.config(1);
        try (JdbcDrivers drivers = new JdbcDrivers()) {
            Connection connection = new DataSourceFactory(drivers, new TestPrivateStorage()).direct(config).getConnection();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE " + arguments[0] + ".tasks SET state = 'done'");
            }
            // The connection is never closed and the driver's class loader is not closed either: the process ends here, in the middle of the transaction.
            Runtime.getRuntime().halt(CRASH_EXIT);
        }
    }
}
