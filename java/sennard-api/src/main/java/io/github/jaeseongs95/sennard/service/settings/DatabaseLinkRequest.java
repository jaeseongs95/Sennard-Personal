// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings;

import java.nio.file.Path;

import io.github.jaeseongs95.sennard.service.database.JdbcUrlPassword;

/**
 * What the admin page saves to link the business database: the user's JDBC driver (a jar or a folder of jars), the URL, the account's name, a reference to the file that holds
 * the account's password (the first line of that file) and how the link is protected. The password itself is never part of this record, and a URL that carries one is refused.
 * Whether the URL is one Sennard can use (its product, its TLS option) is the store's own check ({@code SennardDatabaseConfig}), made when the link is prepared.
 */
public record DatabaseLinkRequest(Path driverPath, String jdbcUrl, String accountName, Path passwordRef, DatabaseTls tls) {
    public static final int JDBC_URL_MAX_LENGTH = 2048;
    public static final int ACCOUNT_MAX_LENGTH = 255;
    public static final int PATH_MAX_LENGTH = 1024;

    public DatabaseLinkRequest {
        SettingsChecks.absolute("driverPath", driverPath);
        SettingsChecks.text("jdbcUrl", jdbcUrl, JDBC_URL_MAX_LENGTH);
        if (JdbcUrlPassword.carriedBy(jdbcUrl)) {
            throw new SettingsException(SettingsCode.INVALID_SETTING, "jdbcUrl carries a password; the password stays in the file passwordRef points at.");
        }
        if (!jdbcUrl.startsWith("jdbc:")) {
            throw new SettingsException(SettingsCode.INVALID_SETTING, "jdbcUrl is not a JDBC URL (it does not start with jdbc:).");
        }
        SettingsChecks.text("accountName", accountName, ACCOUNT_MAX_LENGTH);
        SettingsChecks.absolute("passwordRef", passwordRef);
        SettingsChecks.required("tls", tls);
        if (driverPath.toString().length() > PATH_MAX_LENGTH || passwordRef.toString().length() > PATH_MAX_LENGTH) {
            throw new SettingsException(SettingsCode.INVALID_SETTING, "a path is longer than " + PATH_MAX_LENGTH + " characters.");
        }
    }
}
