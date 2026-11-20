// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.Optional;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.jaeseongs95.sennard.admincli.AdminReset;
import io.github.jaeseongs95.sennard.admincli.AdminResetContext;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.service.settings.DatabaseLinkSettings;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistry;
import io.github.jaeseongs95.sennard.service.settings.PreLinkAdminRecords;
import io.github.jaeseongs95.sennard.service.store.pg.BusinessDb;
import io.github.jaeseongs95.sennard.service.store.pg.PreparedStore;
import io.github.jaeseongs95.sennard.service.store.pg.StorePhase;
import tools.jackson.databind.node.ObjectNode;

/**
 * AGS3-D-0254 (audit 2): {@code sennard admin-reset} while the section of the signed admin log before the link is open, that is, while the business database was never ready. The log is
 * in the settings database alone then, and a passkey lost after the first registration (or after a link that failed) can be reset without the business database, which may not be
 * set at all. Once the business database was ready the log is the business database's too and the reset needs it, as it did.
 *
 * <p>The command runs through its entrance ({@link AdminResetContext}), given the settings folder of the server of the test and no business database setting, the way a person at
 * the installation would run it.
 *
 * <pre>
 * a1  first key      a key registered before the link: the reset empties the registry, moves the section's records to the archive, and the first key registers again (TOFU)
 * a2  failed link    the same after a link that failed (the store is FAILED): the link settings stay, so the user can link again with the new first key
 * a3  dry run        without --yes nothing moves, and it says what would
 * a4  after the link the business database ready once, the section is closed: the reset needs the business database and ends with ADMIN_RESET_DATABASE_UNREACHABLE when none is linked
 * a5  locked file    the settings database's file is held by another opener (the server a hook started again after the command stopped it): ADMIN_RESET_SETTINGS_LOCKED, no retry, nothing
 *                    changed, and the same command succeeds once the file is free
 * </pre>
 */
@Tag("business-db")
class AdminResetBeforeLinkServerTest {
    /** The settings of the command: the server's settings folder, and no business database. */
    private static String[] settingsOf(ToolServerSupport sennard) {
        return new String[] {"--sennard.settings.directory=" + sennard.server.stateDirectory().resolve("settings")};
    }

    private static long count(ToolServerSupport sennard, String table) {
        return sennard.server.context.getBean("settingsJdbcClient", JdbcClient.class).sql("SELECT count(*) FROM sennard." + table).query(Long.class).single();
    }

    private static ObjectNode link(Path driverPath, String url, String account, Path passwordFile) {
        return ToolServerSupport.MAPPER.createObjectNode().put("op", "DATABASE_LINK").put("driverPath", driverPath.toString()).put("jdbcUrl", url).put("accountName", account)
                .put("passwordRef", passwordFile.toString()).put("tls", "LOOPBACK_PLAINTEXT");
    }

    private static void awaitPhase(ToolServerSupport sennard, StorePhase wanted) throws InterruptedException {
        PreparedStore prepared = sennard.server.bean(PreparedStore.class);
        SessionServerSupport.await(() -> prepared.status().phase() == wanted ? Optional.of(true) : Optional.empty(), "the store to be " + wanted);
    }

    @Test
    void a1AKeyRegisteredBeforeTheLinkIsResetWithoutTheBusinessDatabaseAndTheFirstKeyRegistersAgain(@TempDir Path home) throws Exception {
        try (ToolServerSupport sennard = ToolServerSupport.startBeforeTheLink()) {
            assertEquals(StorePhase.NOT_CONFIGURED, sennard.server.bean(PreparedStore.class).status().phase());
            ToolServerSupport.Passkey lost = new ToolServerSupport.Passkey();
            sennard.sign(lost, lost.addChange(), true, null);
            assertEquals(1, sennard.server.bean(PasskeyRegistry.class).list().size());
            assertEquals(1, sennard.server.bean(PreLinkAdminRecords.class).list().size());
            assertFalse(sennard.server.bean(PreLinkAdminRecords.class).closed());

            AdminReset.Result done = AdminResetContext.run(true, Clock.systemUTC(), home, settingsOf(sennard));
            assertNull(done.reset(), "there is no business log to archive");
            assertEquals(1, done.summary().keys());

            assertTrue(sennard.server.bean(PasskeyRegistry.class).list().isEmpty(), "the registry holds no key of the lost passkey");
            assertEquals(0, count(sennard, "pre_link_admin_records"));
            assertEquals(1, count(sennard, "pre_link_admin_records_archive"), "the record before the link moved to the archive");
            assertFalse(sennard.server.bean(PreLinkAdminRecords.class).closed(), "the section is open as it was");
            assertEquals(0, sennard.state().path("keys").size());

            ToolServerSupport.Passkey next = new ToolServerSupport.Passkey();
            sennard.sign(next, next.addChange(), true, null);
            assertEquals(1, sennard.state().path("keys").size(), "the first key registers again");
            assertEquals(next.credentialId, sennard.state().path("keys").get(0).path("credentialId").asString());
        }
    }

    @Test
    void a2AKeyRegisteredBeforeALinkThatFailedIsResetAndTheLinkSettingsStayForTheNextFirstKey(@TempDir Path home) throws Exception {
        Path decoyPassword = Files.writeString(home.resolve("decoy.pw"), "decoy\n").toAbsolutePath();
        try (ToolServerSupport sennard = ToolServerSupport.startBeforeTheLink()) {
            ToolServerSupport.Passkey lost = new ToolServerSupport.Passkey();
            sennard.sign(lost, lost.addChange(), true, null);
            // The link names a database nothing listens on: the store tries and fails, and the section stays open, with the link settings saved.
            sennard.sign(lost, link(BusinessDb.config().driverPath().toAbsolutePath(), "jdbc:postgresql://127.0.0.1:1/none", "decoy", decoyPassword));
            awaitPhase(sennard, StorePhase.FAILED);
            assertFalse(sennard.server.bean(PreLinkAdminRecords.class).closed());
            assertTrue(sennard.server.bean(DatabaseLinkSettings.class).find().isPresent());

            AdminReset.Result done = AdminResetContext.run(true, Clock.systemUTC(), home, settingsOf(sennard));
            assertNull(done.reset());
            assertEquals(1, done.summary().keys());

            assertTrue(sennard.server.bean(PasskeyRegistry.class).list().isEmpty());
            assertEquals(0, count(sennard, "pre_link_admin_records"));
            assertEquals(2, count(sennard, "pre_link_admin_records_archive"), "the key's record and the link's record moved to the archive");
            assertTrue(sennard.server.bean(DatabaseLinkSettings.class).find().isPresent(), "the link settings are no signed record and stay: the user links again with the new first key");
            ToolServerSupport.Passkey next = new ToolServerSupport.Passkey();
            sennard.sign(next, next.addChange(), true, null);
            assertEquals(1, sennard.state().path("keys").size());
        }
    }

    @Test
    void a3WithoutYesNothingMovesAndItSaysWhatWould(@TempDir Path home) throws Exception {
        try (ToolServerSupport sennard = ToolServerSupport.startBeforeTheLink()) {
            ToolServerSupport.Passkey lost = new ToolServerSupport.Passkey();
            sennard.sign(lost, lost.addChange(), true, null);
            AdminReset.Result dry = AdminResetContext.run(false, Clock.systemUTC(), home, settingsOf(sennard));
            assertNull(dry.reset());
            assertEquals(1, dry.summary().keys());
            assertEquals(1, sennard.server.bean(PasskeyRegistry.class).list().size(), "not confirmed: the registry keeps its key");
            assertEquals(1, count(sennard, "pre_link_admin_records"));
            assertEquals(0, count(sennard, "pre_link_admin_records_archive"));
        }
    }

    /**
     * A real lock, no substitute: another opener of the database file takes the JDK file lock that H2 takes on it, and the command's open then fails the way it does while a server
     * holds the file ("The file is locked"). H2 itself would share the database with a second connection of this JVM, so the holder is the file lock and not a connection.
     */
    @Test
    void a5ASettingsFileHeldByAnotherOpenerEndsWithTheLockedCodeWithoutRetryAndTheNextRunSucceeds(@TempDir Path home) throws Exception {
        Path folder = home.resolve("settings");
        String[] settings = {"--sennard.settings.directory=" + folder};
        // The first run makes the database (the section is open: no business database is needed) and closes it.
        assertNull(AdminResetContext.run(false, Clock.systemUTC(), home, settings).reset());
        Path file = folder.resolve("sennard-settings.mv.db");
        assertTrue(Files.isRegularFile(file), "the first run made the database file");

        try (FileChannel holder = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE); FileLock held = holder.tryLock()) {
            assertNotNull(held, "the file is held by this test");
            UnavailableException locked = assertThrows(UnavailableException.class, () -> AdminResetContext.run(true, Clock.systemUTC(), home, settings));
            assertEquals(UnavailableCode.ADMIN_RESET_SETTINGS_LOCKED, locked.code());
        }

        AdminReset.Result done = AdminResetContext.run(true, Clock.systemUTC(), home, settings);
        assertNull(done.reset());
        assertEquals(0, done.summary().keys(), "nothing was changed by the locked run, and the free file is reset as before");
    }

    @Test
    void a4OnceTheBusinessDatabaseWasReadyTheResetNeedsItAndSaysSoWhenNoneIsLinked(@TempDir Path home) throws Exception {
        try (ToolServerSupport sennard = ToolServerSupport.start()) {
            ToolServerSupport.Passkey owner = sennard.owner();
            assertTrue(sennard.server.bean(PreLinkAdminRecords.class).closed(), "the business database was ready: the section is closed");
            // The settings database of the state folder holds no link (it is the one source of the business database), so the command has none to reach.
            sennard.server.context.getBean("settingsJdbcClient", JdbcClient.class).sql("DELETE FROM sennard.database_link").update();
            assertEquals(0, count(sennard, "database_link"));

            UnavailableException unreachable = assertThrows(UnavailableException.class, () -> AdminResetContext.run(true, Clock.systemUTC(), home, settingsOf(sennard)));
            assertEquals(UnavailableCode.ADMIN_RESET_DATABASE_UNREACHABLE, unreachable.code());
            assertEquals(1, sennard.server.bean(PasskeyRegistry.class).list().size(), "the refused reset changed nothing");
            assertEquals(owner.credentialId, sennard.state().path("keys").get(0).path("credentialId").asString());
        }
    }
}
