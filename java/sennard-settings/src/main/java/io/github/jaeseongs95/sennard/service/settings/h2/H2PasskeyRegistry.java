// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings.h2;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jaeseongs95.sennard.domain.Timestamps;
import io.github.jaeseongs95.sennard.service.settings.PasskeyAlgorithm;
import io.github.jaeseongs95.sennard.service.settings.PasskeyCredential;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistration;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistry;
import io.github.jaeseongs95.sennard.service.settings.PasskeyState;
import io.github.jaeseongs95.sennard.service.settings.SettingsCode;
import io.github.jaeseongs95.sennard.service.settings.SettingsException;
import io.github.jaeseongs95.sennard.service.settings.SettingsTransactions;
import io.github.jaeseongs95.sennard.service.settings.StoredEnum;

/**
 * The passkey registry in the settings database. The state and the algorithm are enum names; no schema list backs them, {@link StoredEnum} checks them.
 *
 * <p>The three writes are service methods of the settings database, each with its one {@code @Transactional}, so a caller of the business side (a business transaction of another DataSource)
 * and a caller of the settings side (the pre-link use cases, whose settings transaction the call joins) both reach them. The unique key and the conditional UPDATE decide, and a caller
 * that lost the race reads what the winner wrote and is answered with it: registering the same key twice, or revoking a key twice, succeeds twice and writes once.
 */
@Service
public class H2PasskeyRegistry implements PasskeyRegistry {
    private static final String SELECT = "SELECT credential_id, public_key, algorithm, label, state, registered_at, revoked_at FROM sennard.passkey_credentials";

    private final JdbcClient jdbc;
    private final Clock clock;

    H2PasskeyRegistry(@Qualifier("settingsJdbc") JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * The INSERT decides. A unique-key violation means the credential id is held, by an earlier attempt or by a caller that registered it and has committed: it is read (only read,
     * nothing is written after the violation), and the same key bytes and algorithm are the same registration, answered with the held key; other bytes are PASSKEY_EXISTS. The settings
     * database is H2, where a statement that violates a constraint is rolled back alone and the transaction goes on, so the read belongs to the same transaction (a database that
     * ends the transaction at a violation, as PostgreSQL does, would need a conditional INSERT here).
     *
     * <p>What H2 2.4.240 does when another transaction's INSERT of the same id is not committed yet is not to wait: the unique check of a secondary index (the primary key of a text
     * column is one) finds the uncommitted entry and throws CONCURRENT_UPDATE_1 (SQLState 40001) at once, where only a committed entry is a duplicate key
     * ({@code MVSecondaryIndex.checkUnique}, org.h2.mvstore.db, version-2.4.240, lines 198-228; {@code MVTable.addRow}, lines 515-532, rolls the statement back to its savepoint and
     * rethrows); at other moments the statement waits for the lock and ends with LOCK_TIMEOUT_1 (50200). The error code translator of the settings JdbcClient
     * ({@code SettingsStoreConfiguration}) makes both a {@code ConcurrencyFailureException} (the second is a {@code CannotAcquireLockException}), which this method lets through: the entrances state it, once, as the retryable conflict every race is
     * answered with ({@code PersistenceErrors}), and there is no retry, no wait and no lock of ours; the same call made again once the other caller has ended is answered as above. So two
     * callers that register one key both succeed when the first has committed before the second inserts, and a caller that overlaps an uncommitted first one is refused with that conflict
     * (the page sends its request again).
     */
    @Override
    @Transactional(transactionManager = SettingsTransactions.MANAGER)
    public PasskeyCredential register(PasskeyRegistration registration) {
        try {
            jdbc.sql("INSERT INTO sennard.passkey_credentials (credential_id, public_key, algorithm, label, state, registered_at) VALUES (?, ?, ?, ?, ?, ?)")
                    .params(registration.credentialId(), registration.publicKey(), registration.algorithm().name(), registration.label(), PasskeyState.ACTIVE.name(),
                            Timestamps.toTimestamp(clock.instant()))
                    .update();
        } catch (DuplicateKeyException registered) {
            PasskeyCredential held = find(registration.credentialId())
                    .orElseThrow(() -> new SettingsException(SettingsCode.SETTINGS_UNAVAILABLE, "The credential id violated the unique key but no row holds it.", registered));
            if (held.registration().publicKey().equals(registration.publicKey()) && held.registration().algorithm() == registration.algorithm()) {
                return held;
            }
            throw new SettingsException(SettingsCode.PASSKEY_EXISTS, "The credential id is registered with another key.", registered);
        }
        return find(registration.credentialId()).orElseThrow(() -> new SettingsException(SettingsCode.SETTINGS_UNAVAILABLE, "The registered passkey was not found again."));
    }

    @Override
    public Optional<PasskeyCredential> find(String credentialId) {
        return jdbc.sql(SELECT + " WHERE credential_id = ?").param(credentialId).query(H2PasskeyRegistry::map).optional();
    }

    @Override
    public List<PasskeyCredential> list() {
        return jdbc.sql(SELECT + " ORDER BY registered_at, credential_id").query(H2PasskeyRegistry::map).list();
    }

    /**
     * One conditional UPDATE decides: the row is revoked by the call whose statement changed it (1 row). A call that changed nothing finds the row revoked by another (it is
     * answered with it: the key is revoked, which is what was asked) or finds no row.
     */
    @Override
    @Transactional(transactionManager = SettingsTransactions.MANAGER)
    public PasskeyCredential revoke(String credentialId) {
        jdbc.sql("UPDATE sennard.passkey_credentials SET state = ?, revoked_at = ? WHERE credential_id = ? AND state = ?")
                .params(PasskeyState.REVOKED.name(), Timestamps.toTimestamp(clock.instant()), credentialId, PasskeyState.ACTIVE.name()).update();
        return find(credentialId).orElseThrow(() -> new SettingsException(SettingsCode.PASSKEY_NOT_FOUND, "No passkey has this credential id."));
    }

    /** One DELETE; a service method of its own, as the admin reset (an entrance of the business side) calls it. */
    @Override
    @Transactional(transactionManager = SettingsTransactions.MANAGER)
    public void clear() {
        jdbc.sql("DELETE FROM sennard.passkey_credentials").update();
    }

    private static PasskeyCredential map(ResultSet row, int rowNumber) throws SQLException {
        PasskeyRegistration registration = new PasskeyRegistration(row.getString("credential_id"), row.getString("public_key"),
                StoredEnum.ofStored(PasskeyAlgorithm.class, "algorithm", row.getString("algorithm")), row.getString("label"));
        LocalDateTime revokedAt = row.getObject("revoked_at", LocalDateTime.class);
        return new PasskeyCredential(registration, StoredEnum.ofStored(PasskeyState.class, "state", row.getString("state")),
                Timestamps.toInstant(row.getObject("registered_at", LocalDateTime.class)), Timestamps.toInstantOrNull(revokedAt));
    }
}
