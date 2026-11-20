// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings.h2;

import static io.github.jaeseongs95.sennard.service.settings.h2.HeldWriter.LARGER_POOL;
import static io.github.jaeseongs95.sennard.service.settings.h2.HeldWriter.count;
import static io.github.jaeseongs95.sennard.service.settings.h2.HeldWriter.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.ConcurrencyFailureException;

import io.github.jaeseongs95.sennard.service.settings.PasskeyCredential;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistration;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistry;
import io.github.jaeseongs95.sennard.service.settings.PasskeyState;
import io.github.jaeseongs95.sennard.service.settings.SettingsCode;
import io.github.jaeseongs95.sennard.service.settings.SettingsException;

/**
 * AGS3-D-0254 (audit 4): the registry's writes that two callers can make at once (the commit of a key and the page's read, a retry), against the real H2 file database through the
 * real wiring. The callers meet behind a writer the test holds ({@link HeldWriter}).
 *
 * <p>What H2 2.4.240 does with them is two different things, and the cases follow it (org.h2.mvstore.db.MVSecondaryIndex.checkUnique, lines 198-228, and
 * org.h2.mvstore.tx.TransactionMap, lines 354-386, version-2.4.240). An UPDATE of a row that another transaction holds waits for that transaction (up to the lock timeout, 2000 ms by
 * default, Constants.INITIAL_LOCK_TIMEOUT), and the wait is seen in {@code INFORMATION_SCHEMA.SESSIONS.BLOCKER_ID}: the revocations meet that way (r2). An INSERT of a unique key
 * that another transaction inserted and has not committed is not a duplicate key (only a committed entry is): the unique check of the index finds the uncommitted entry and throws
 * CONCURRENT_UPDATE_1 at once, or the statement waits for the lock and ends with LOCK_TIMEOUT_1 (50200), whichever H2 does at the moment; both are a race lost to another writer
 * (r1c does not depend on which). The registrations meet that way (r1, r1b, r1c).
 *
 * <pre>
 * r1   register after commit   two callers register the same key, which another writer has inserted and committed: both succeed, one row
 * r1b  register, other bytes   of two callers that register one credential id the writer has committed, the one with the same key succeeds and the one with other bytes is refused
 *                              (PASSKEY_EXISTS); one row
 * r1c  register, overlapping   two callers register a key whose id another writer has inserted and not committed: the database refuses them, at once (40001) or after a wait that ran
 *                              out (50200), and both are Spring's ConcurrencyFailureException (the entrances answer it as the retryable conflict), the writer's row is the only one, and the
 *                              same calls made after its commit succeed
 * r2   revoke twice            two callers revoke the same key while another writer holds the row: both wait for it (seen in the sessions view) and both succeed, one row
 * </pre>
 */
class SettingsRaceTest {
    private static final String INSERT_KEY = "INSERT INTO sennard.passkey_credentials (credential_id, public_key, algorithm, label, state, registered_at) "
            + "VALUES ('%s', '%s', 'ES256', '%s', 'ACTIVE', CURRENT_TIMESTAMP)";

    @Test
    void r1TwoCallersRegisterTheSameKeyAnotherWriterCommittedAndBothSucceedWithOneRow(@TempDir Path folder) {
        SettingsFixtures.runner(folder).withPropertyValues(LARGER_POOL).run(context -> {
            DataSource source = context.getBean("settingsDataSource", DataSource.class);
            PasskeyRegistry registry = context.getBean(PasskeyRegistry.class);
            PasskeyRegistration key = SettingsFixtures.passkey("race");
            try (HeldWriter writer = HeldWriter.holding(source, INSERT_KEY.formatted(key.credentialId(), key.publicKey(), key.label()))) {
                writer.release();
            }
            try (ExecutorService callers = Executors.newFixedThreadPool(2)) {
                List<Future<PasskeyCredential>> calls = new ArrayList<>();
                calls.add(callers.submit(() -> registry.register(key)));
                calls.add(callers.submit(() -> registry.register(key)));
                for (Future<PasskeyCredential> call : calls) {
                    PasskeyCredential held = within(call);
                    assertEquals(key, held.registration());
                    assertEquals(PasskeyState.ACTIVE, held.state());
                }
            }
            assertEquals(1, count(source, "SELECT COUNT(*) FROM sennard.passkey_credentials"));
        });
    }

    @Test
    void r1bOfTwoCallersThatRegisterOneCommittedCredentialIdTheOneWithOtherBytesIsRefused(@TempDir Path folder) {
        SettingsFixtures.runner(folder).withPropertyValues(LARGER_POOL).run(context -> {
            DataSource source = context.getBean("settingsDataSource", DataSource.class);
            PasskeyRegistry registry = context.getBean(PasskeyRegistry.class);
            PasskeyRegistration key = SettingsFixtures.passkey("race");
            PasskeyRegistration other = new PasskeyRegistration(key.credentialId(), key.publicKey() + "-other", key.algorithm(), "other");
            try (HeldWriter writer = HeldWriter.holding(source, INSERT_KEY.formatted(key.credentialId(), key.publicKey(), key.label()))) {
                writer.release();
            }
            try (ExecutorService callers = Executors.newFixedThreadPool(2)) {
                Future<PasskeyCredential> same = callers.submit(() -> registry.register(key));
                Future<PasskeyCredential> another = callers.submit(() -> registry.register(other));
                assertEquals(key, within(same).registration());
                ExecutionException refused = assertThrows(ExecutionException.class, () -> within(another));
                assertEquals(SettingsCode.PASSKEY_EXISTS, assertInstanceOf(SettingsException.class, refused.getCause()).code());
            }
            assertEquals(1, count(source, "SELECT COUNT(*) FROM sennard.passkey_credentials"));
        });
    }

    @Test
    void r1cTwoCallersRegisterAKeyWhoseIdAnotherWriterInsertedAndNotCommittedAndBothLoseTheRace(@TempDir Path folder) {
        SettingsFixtures.runner(folder).withPropertyValues(LARGER_POOL).run(context -> {
            DataSource source = context.getBean("settingsDataSource", DataSource.class);
            PasskeyRegistry registry = context.getBean(PasskeyRegistry.class);
            PasskeyRegistration key = SettingsFixtures.passkey("race");
            try (HeldWriter writer = HeldWriter.holding(source, INSERT_KEY.formatted(key.credentialId(), key.publicKey(), key.label())); ExecutorService callers = Executors.newFixedThreadPool(2)) {
                List<Future<PasskeyCredential>> calls = new ArrayList<>();
                calls.add(callers.submit(() -> registry.register(key)));
                calls.add(callers.submit(() -> registry.register(key)));
                // Both calls end while the writer is still ahead of them. H2 refuses the second INSERT of an uncommitted unique key either at once (40001) or after a wait for it that ran
                // out (50200); both are a race lost to another writer, and the test does not depend on which of the two comes. Neither is a raw storage error, a query timeout or a
                // code of the settings store: the settings JdbcClient's error code translator makes both a ConcurrencyFailureException, which the entrances answer as the retryable
                // conflict (PersistenceErrors). Nothing here waits, retries or locks.
                for (Future<PasskeyCredential> call : calls) {
                    ExecutionException refused = assertThrows(ExecutionException.class, () -> within(call), () -> "the call should have been refused: " + HeldWriter.describe(call));
                    assertInstanceOf(ConcurrencyFailureException.class, refused.getCause(), "the refusal is Spring's concurrency failure: " + refused.getCause());
                }
                writer.release();
            }
            assertEquals(1, count(source, "SELECT COUNT(*) FROM sennard.passkey_credentials"), "the writer's row is the only one");
            assertEquals(key, registry.register(key).registration(), "the same call after the writer's commit is answered with the held key");
            assertEquals(1, count(source, "SELECT COUNT(*) FROM sennard.passkey_credentials"));
        });
    }

    @Test
    void r2TwoCallersRevokeTheSameKeyWhileAnotherWriterHoldsItsRowAndBothSucceed(@TempDir Path folder) {
        SettingsFixtures.runner(folder).withPropertyValues(LARGER_POOL).run(context -> {
            DataSource source = context.getBean("settingsDataSource", DataSource.class);
            PasskeyRegistry registry = context.getBean(PasskeyRegistry.class);
            PasskeyRegistration key = SettingsFixtures.passkey("race");
            registry.register(key);
            try (HeldWriter writer = HeldWriter.holding(source,
                    "UPDATE sennard.passkey_credentials SET state = 'REVOKED', revoked_at = CURRENT_TIMESTAMP WHERE credential_id = '" + key.credentialId() + "'");
                    ExecutorService callers = Executors.newFixedThreadPool(2)) {
                List<Future<PasskeyCredential>> calls = new ArrayList<>();
                calls.add(callers.submit(() -> registry.revoke(key.credentialId())));
                calls.add(callers.submit(() -> registry.revoke(key.credentialId())));
                writer.awaitBlocked(source, 2, "the two revocations were not both waiting for the writer that is ahead", calls.toArray(Future[]::new));
                writer.release();
                for (Future<PasskeyCredential> call : calls) {
                    assertEquals(PasskeyState.REVOKED, within(call).state());
                }
            }
            assertEquals(PasskeyState.REVOKED, registry.find(key.credentialId()).orElseThrow().state());
            assertEquals(1, count(source, "SELECT COUNT(*) FROM sennard.passkey_credentials"));
        });
    }
}
