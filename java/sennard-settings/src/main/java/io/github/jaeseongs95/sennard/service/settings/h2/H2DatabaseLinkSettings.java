// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings.h2;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.github.jaeseongs95.sennard.domain.Timestamps;
import io.github.jaeseongs95.sennard.service.settings.DatabaseLink;
import io.github.jaeseongs95.sennard.service.settings.DatabaseLinkRequest;
import io.github.jaeseongs95.sennard.service.settings.DatabaseLinkSettings;
import io.github.jaeseongs95.sennard.service.settings.DatabaseTls;
import io.github.jaeseongs95.sennard.service.settings.SettingsCode;
import io.github.jaeseongs95.sennard.service.settings.SettingsException;
import io.github.jaeseongs95.sennard.service.settings.StoredEnum;

/**
 * The link settings in the settings database: one row (the installation has one business database). The password is not here, only the path of the file that holds it. The
 * enum {@link DatabaseTls} is checked by {@link StoredEnum} as the passkey registry's enums are: the schema has no list of its own.
 */
@Repository
public class H2DatabaseLinkSettings implements DatabaseLinkSettings {
    private static final int LINK_ID = 1;

    private final JdbcClient jdbc;
    private final Clock clock;

    H2DatabaseLinkSettings(@Qualifier("settingsJdbc") JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * One MERGE statement (H2's own form: this store is H2's alone) replaces the row or makes the first: the registry opens no transaction, one statement decides. A use case that
     * writes the link with a signed record opens the settings transaction itself, and this statement joins it.
     */
    @Override
    public DatabaseLink save(DatabaseLinkRequest request) {
        jdbc.sql("MERGE INTO sennard.database_link (link_id, driver_path, jdbc_url, db_user, password_ref, tls_policy, saved_at) KEY (link_id) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(LINK_ID, request.driverPath().toString(), request.jdbcUrl(), request.accountName(), request.passwordRef().toString(), request.tls().name(),
                        Timestamps.toTimestamp(clock.instant()))
                .update();
        return find().orElseThrow(() -> new SettingsException(SettingsCode.SETTINGS_UNAVAILABLE, "The saved link settings were not found again."));
    }

    @Override
    public Optional<DatabaseLink> find() {
        return jdbc.sql("SELECT driver_path, jdbc_url, db_user, password_ref, tls_policy, saved_at FROM sennard.database_link WHERE link_id = ?").param(LINK_ID)
                .query(H2DatabaseLinkSettings::map).optional();
    }

    private static DatabaseLink map(ResultSet row, int rowNumber) throws SQLException {
        DatabaseLinkRequest request = new DatabaseLinkRequest(Path.of(row.getString("driver_path")), row.getString("jdbc_url"), row.getString("db_user"),
                Path.of(row.getString("password_ref")), StoredEnum.ofStored(DatabaseTls.class, "tls_policy", row.getString("tls_policy")));
        return new DatabaseLink(request, Timestamps.toInstant(row.getObject("saved_at", LocalDateTime.class)));
    }
}
