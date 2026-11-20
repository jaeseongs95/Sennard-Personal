// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.jaeseongs95.sennard.admincli.AdminReset;
import io.github.jaeseongs95.sennard.admincli.AdminResetContext;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistry;
import io.github.jaeseongs95.sennard.service.settings.PreLinkAdminRecords;
import io.github.jaeseongs95.sennard.service.store.pg.SennardDatabaseConfig;
import io.github.jaeseongs95.sennard.service.store.pg.BusinessDb;
import io.github.jaeseongs95.sennard.service.store.pg.PreparedStore;
import io.github.jaeseongs95.sennard.service.store.pg.StorePhase;
import tools.jackson.databind.node.ObjectNode;

/**
 * AGS3-D-0254 (audit 6): the admin reset command's steps against a server whose log is in both places, the records signed before the link (the settings database) and the ones
 * signed after it (the business database), the way a person who linked from the admin page has it. The command runs through its entrance ({@link AdminResetContext}) and finds the
 * business database in the link the page saved (the one source of the business database).
 *
 * <p>The order of the steps is the registry, then the records before the link, then the business log, because a run that stops between two of them must be one that leaves no
 * key to sign with. The tests stop the run the way a crash would, by doing the steps that came first with the services' own methods, and show that the state it leaves is
 * one nothing can sign in (the log fails its integrity, which takes neither a first key nor a signature), and that running the command again finishes it.
 *
 * <pre>
 * s1  every step     the registry is emptied, the two records before the link are archived, the business log is archived and a reset is recorded; the first key registers again
 * s2  stop after 1   the registry is empty and nothing else moved: no key and no signature is taken; the next run finishes the reset
 * s3  stop after 2   the registry is empty and the records before the link are archived: the same
 * s4  run again      a finished reset run again fails nothing and moves nothing more
 * </pre>
 */
@Tag("business-db")
class AdminResetStepsServerTest {
    private static String[] settingsOf(ToolServerSupport sennard) {
        return new String[] {"--sennard.settings.directory=" + sennard.server.stateDirectory().resolve("settings")};
    }

    private static ObjectNode link(SennardDatabaseConfig config) {
        return ToolServerSupport.MAPPER.createObjectNode().put("op", "DATABASE_LINK").put("driverPath", config.driverPath().toAbsolutePath().toString()).put("jdbcUrl", config.jdbcUrl())
                .put("accountName", config.ownerUser()).put("passwordRef", config.ownerPasswordRef().toAbsolutePath().toString()).put("tls", config.tlsPolicy().name());
    }

    private static long settingsCount(ToolServerSupport sennard, String table) {
        return sennard.server.context.getBean("settingsJdbcClient", JdbcClient.class).sql("SELECT count(*) FROM sennard." + table).query(Long.class).single();
    }

    private static long businessCount(ToolServerSupport sennard, String table) {
        return SessionServerSupport.count(sennard.server, "SELECT count(*) FROM sennard." + table);
    }

    private static void awaitReady(ToolServerSupport sennard) throws InterruptedException {
        PreparedStore prepared = sennard.server.bean(PreparedStore.class);
        SessionServerSupport.await(() -> prepared.status().phase() == StorePhase.READY ? Optional.of(true) : Optional.empty(), "the store to be READY");
    }

    /** A server linked from its admin page: two records before the link (the key, the link) and one after it (a role), and the section closed. */
    private static ToolServerSupport linked() throws Exception {
        // Every test links a server of its own to the database, so each starts it from nothing (the once-a-class reset of SharedDatabase would leave the earlier test's log in it).
        BusinessDb.reset();
        ToolServerSupport sennard = ToolServerSupport.startBeforeTheLink();
        try {
            ToolServerSupport.Passkey owner = new ToolServerSupport.Passkey();
            sennard.sign(owner, owner.addChange(), true, null);
            sennard.sign(owner, link(BusinessDb.config()));
            awaitReady(sennard);
            sennard.sign(owner, ToolServerSupport.roleAdd("role-1", "Reviewer"));
            assertEquals(2, settingsCount(sennard, "pre_link_admin_records"));
            assertEquals(1, businessCount(sennard, "admin_log"));
            assertTrue(sennard.server.bean(PreLinkAdminRecords.class).closed());
            return sennard;
        } catch (Exception | Error failure) {
            sennard.close();
            throw failure;
        }
    }

    /** What a finished reset leaves, wherever the run began. */
    private static void assertReset(ToolServerSupport sennard) throws Exception {
        assertTrue(sennard.server.bean(PasskeyRegistry.class).list().isEmpty(), "the registry is empty");
        assertEquals(0, settingsCount(sennard, "pre_link_admin_records"));
        assertEquals(2, settingsCount(sennard, "pre_link_admin_records_archive"), "the two records before the link are in the archive, once");
        assertEquals(0, businessCount(sennard, "admin_log"));
        assertEquals(1, businessCount(sennard, "admin_log_archive"));
        assertEquals(0, sennard.state().path("keys").size());
        assertEquals(0, sennard.state().path("failures").size());
        ToolServerSupport.Passkey next = new ToolServerSupport.Passkey();
        sennard.sign(next, next.addChange(), true, null);
        assertEquals(1, sennard.state().path("keys").size(), "the first key registers again");
    }

    /** What the state a stopped run leaves refuses: the log fails its integrity, so it takes neither a first key nor a new signature. */
    private static void assertNothingCanSign(ToolServerSupport sennard) throws Exception {
        assertFalse(sennard.state().path("failures").isEmpty(), "the log fails its integrity: the keys its records name are not in the registry");
        RawHttp.Response firstKey = sennard.post("/api/intent", ToolServerSupport.MAPPER.createObjectNode().<ObjectNode>set("change", new ToolServerSupport.Passkey().addChange()).put("bootstrap", true));
        assertEquals(400, firstKey.status(), firstKey.text());
        assertEquals("ADMIN_RECORD_ALTERED", SessionServerSupport.json(firstKey).path("code").asString());
        RawHttp.Response signature = sennard.post("/api/intent",
                ToolServerSupport.MAPPER.createObjectNode().<ObjectNode>set("change", ToolServerSupport.roleAdd("role-2", "Other")).put("bootstrap", false));
        assertEquals(400, signature.status(), signature.text());
        assertEquals("ADMIN_RECORD_ALTERED", SessionServerSupport.json(signature).path("code").asString());
    }

    @Test
    void s1EveryStepRunsAndTheFirstKeyRegistersAgain(@TempDir Path home) throws Exception {
        try (ToolServerSupport sennard = linked()) {
            AdminReset.Result done = AdminResetContext.run(true, Clock.systemUTC(), home, settingsOf(sennard));
            assertNotNull(done.reset(), "the business log was archived and the reset recorded");
            assertEquals(1, done.reset().archived());
            assertEquals(3, done.summary().lastSeq(), "one sequence: two records before the link, one after");
            assertEquals(1, done.summary().keys());
            assertEquals(1, done.summary().roles());
            assertReset(sennard);
            assertEquals(1, sennard.state().path("resets").size(), "the page shows that a reset happened");
        }
    }

    @Test
    void s2ARunThatStoppedAfterTheRegistryWasEmptiedLeavesNothingThatCanSignAndTheNextRunFinishesIt(@TempDir Path home) throws Exception {
        try (ToolServerSupport sennard = linked()) {
            sennard.server.bean(PasskeyRegistry.class).clear();
            assertEquals(2, settingsCount(sennard, "pre_link_admin_records"), "nothing else moved");
            assertEquals(1, businessCount(sennard, "admin_log"));
            assertNothingCanSign(sennard);

            AdminReset.Result done = AdminResetContext.run(true, Clock.systemUTC(), home, settingsOf(sennard));
            assertNotNull(done.reset());
            assertReset(sennard);
        }
    }

    @Test
    void s3ARunThatStoppedAfterTheRecordsBeforeTheLinkMovedLeavesNothingThatCanSignAndTheNextRunFinishesIt(@TempDir Path home) throws Exception {
        try (ToolServerSupport sennard = linked()) {
            sennard.server.bean(PasskeyRegistry.class).clear();
            sennard.server.bean(PreLinkAdminRecords.class).archive();
            assertEquals(0, settingsCount(sennard, "pre_link_admin_records"));
            assertEquals(2, settingsCount(sennard, "pre_link_admin_records_archive"));
            assertEquals(1, businessCount(sennard, "admin_log"), "the business log was not archived yet");
            assertNothingCanSign(sennard);

            AdminReset.Result done = AdminResetContext.run(true, Clock.systemUTC(), home, settingsOf(sennard));
            assertNotNull(done.reset());
            assertEquals(1, done.reset().archived());
            assertReset(sennard);
        }
    }

    @Test
    void s4AFinishedResetRunAgainFailsNothingAndMovesNothingMore(@TempDir Path home) throws Exception {
        try (ToolServerSupport sennard = linked()) {
            AdminResetContext.run(true, Clock.systemUTC(), home, settingsOf(sennard));
            AdminReset.Result again = AdminResetContext.run(true, Clock.systemUTC(), home, settingsOf(sennard));
            assertEquals(0, again.reset().archived(), "the second run had no record to archive");
            assertEquals(0, again.summary().keys());
            assertReset(sennard);
        }
    }
}
