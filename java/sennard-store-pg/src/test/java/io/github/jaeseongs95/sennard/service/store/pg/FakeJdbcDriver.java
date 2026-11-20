// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * A JDBC driver of no database, which {@link JdbcDriversTest} packs into a jar of its own to show how the product finds the user's driver: it is a {@link Driver} for the URL form
 * {@code jdbc:fake:}, and it takes no connection (it is never asked for one). It names nothing but the JDK, because the jar it is packed in is read by a class loader whose parent
 * is the platform class loader: the test classes are not visible there, as no class of the application is visible to a user's driver.
 */
public final class FakeJdbcDriver implements Driver {
    /** The form of URL this driver takes. */
    public static final String URL_PREFIX = "jdbc:fake:";

    @Override
    public Connection connect(String url, Properties info) {
        return null; // the JDBC contract for a URL the driver does not take; this driver takes no connection at all
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(URL_PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("The fake driver logs nothing.");
    }
}
