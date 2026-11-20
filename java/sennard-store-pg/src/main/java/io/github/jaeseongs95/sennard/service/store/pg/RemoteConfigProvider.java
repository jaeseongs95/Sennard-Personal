// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.nio.file.Path;

import io.github.jaeseongs95.sennard.service.store.pg.SennardDatabaseConfig.TlsPolicy;

/**
 * Connection settings for a database the service does not start: the user's JDBC URL, the one account, the file that holds its password and the user's JDBC driver, as the
 * user gives them (the link the personal edition's admin page keeps in the settings database). The product's embedded PostgreSQL is gone, so this is the
 * product's only provider and the tests use it too.
 *
 * <p>It does not prepare anything: the user (in the tests, the fixture) created the database and the one account, which owns the
 * schemas and runs the migrations and the business statements. The TLS rule is enforced by {@link SennardDatabaseConfig}: a URL that asks
 * for no TLS (no sslmode) is plain text, and plain text is accepted only for an IP-literal loopback host.
 */
public final class RemoteConfigProvider implements DatabaseConfigProvider {
    private final String jdbcUrl;
    private final String ownerUser;
    private final Path ownerPasswordRef;
    private final Path driverPath;
    private final int poolSize;
    /** How the link is protected as the user chose it (the admin page); null where the URL tells (the store's own tests). */
    private final TlsPolicy chosenPolicy;

    public RemoteConfigProvider(String jdbcUrl, String ownerUser, Path ownerPasswordRef, Path driverPath, int poolSize) {
        this(jdbcUrl, ownerUser, ownerPasswordRef, driverPath, poolSize, null);
    }

    /** The link the user signed on the admin page: the protection is the one they chose, and {@link SennardDatabaseConfig} holds the URL to it. */
    public RemoteConfigProvider(String jdbcUrl, String ownerUser, Path ownerPasswordRef, Path driverPath, int poolSize, TlsPolicy chosenPolicy) {
        this.jdbcUrl = jdbcUrl;
        this.ownerUser = ownerUser;
        this.ownerPasswordRef = ownerPasswordRef;
        this.driverPath = driverPath;
        this.poolSize = poolSize;
        this.chosenPolicy = chosenPolicy;
    }

    @Override
    public SennardDatabaseConfig provide() {
        TlsPolicy policy = chosenPolicy != null ? chosenPolicy : SennardDatabaseConfig.asksForTls(jdbcUrl) ? TlsPolicy.VERIFY_FULL : TlsPolicy.LOOPBACK_PLAINTEXT;
        return new SennardDatabaseConfig(jdbcUrl, ownerUser, ownerPasswordRef, driverPath, policy, poolSize);
    }
}
