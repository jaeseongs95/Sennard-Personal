// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.jaeseongs95.sennard.service.settings.PreLinkAdminRecord;
import io.github.jaeseongs95.sennard.service.settings.PreLinkAdminRecords;
import io.github.jaeseongs95.sennard.service.settings.SettingsCode;
import io.github.jaeseongs95.sennard.service.settings.SettingsException;
import io.github.jaeseongs95.sennard.service.store.pg.SennardDatabaseConfig;
import io.github.jaeseongs95.sennard.service.store.pg.BusinessDb;
import io.github.jaeseongs95.sennard.service.store.pg.PreparedStore;
import io.github.jaeseongs95.sennard.service.store.pg.StorePhase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * AGS3-D-0254: the admin page before the business database is linked. A server that names no database starts with its store waiting (NOT_CONFIGURED); the page is then the
 * settings database's: the first passkey registers itself there, and the user links the business database by signing a {@code DATABASE_LINK} with it. When the database is
 * ready, the signed log goes on in the business database after the records made before it, and the section before the link is closed.
 *
 * <pre>
 * p8   link, ready   the first key and the link are signed before the link; the store becomes ready; the business log's first record of a chain names the last record before
 *                    the link of that chain as its prevTargetHash, and the whole log verifies (no failed record, one sequence)
 * p9   closed        once ready, the section before the link takes no record; a link signed then is a record of the business log, the store stays ready, and the page says that the link
 *                    is used after a restart
 * p10  no key source a {@code sennard.store.database.*} key in the settings file is no source of the business database and does not lock the page
 * p13  no password   no record before the link, no link setting and no page state holds the password (the link names the file that holds it); a link that carries one is refused
 * </pre>
 */
@Tag("business-db")
class PreLinkAdminServerTest {
    /** What the decoy password file holds: a value that must be nowhere but in that file. */
    private static final String SENTINEL = "pw-sentinel-" + UUID.randomUUID();

    private static String code(RawHttp.Response response) {
        return SessionServerSupport.json(response).path("code").asString();
    }

    private static String reason(RawHttp.Response response) {
        return SessionServerSupport.json(response).path("details").path("reason").asString();
    }

    private static ObjectNode link(Path driverPath, String url, String account, Path passwordFile, String tls) {
        return ToolServerSupport.MAPPER.createObjectNode().put("op", "DATABASE_LINK").put("driverPath", driverPath.toString()).put("jdbcUrl", url).put("accountName", account)
                .put("passwordRef", passwordFile.toString()).put("tls", tls);
    }

    private static ObjectNode link(SennardDatabaseConfig config) {
        return link(config.driverPath().toAbsolutePath(), config.jdbcUrl(), config.ownerUser(), config.ownerPasswordRef().toAbsolutePath(), config.tlsPolicy().name());
    }

    /** The number of cells of the settings database's tables for the link and the records before it that hold this text. */
    private static long cellsHolding(ToolServerSupport sennard, String text) {
        JdbcClient settings = sennard.server.context.getBean("settingsJdbcClient", JdbcClient.class);
        long found = 0;
        for (String column : new String[] {"target", "intent", "credential_id", "authenticator_data", "client_data_json", "signature", "record_hash"}) {
            found += settings.sql("SELECT count(*) FROM sennard.pre_link_admin_records WHERE POSITION(? IN " + column + ") > 0").param(text).query(Long.class).single();
            found += settings.sql("SELECT count(*) FROM sennard.pre_link_admin_records_archive WHERE POSITION(? IN " + column + ") > 0").param(text).query(Long.class).single();
        }
        for (String column : new String[] {"driver_path", "jdbc_url", "db_user", "password_ref", "tls_policy"}) {
            found += settings.sql("SELECT count(*) FROM sennard.database_link WHERE POSITION(? IN " + column + ") > 0").param(text).query(Long.class).single();
        }
        return found;
    }

    private static void awaitPhase(ToolServerSupport sennard, StorePhase wanted) throws InterruptedException {
        PreparedStore prepared = sennard.server.bean(PreparedStore.class);
        SessionServerSupport.await(() -> prepared.status().phase() == wanted ? Optional.of(true) : Optional.empty(), "the store to be " + wanted);
    }

    @Test
    void p8p9p13TheFirstKeyAndTheLinkAreSignedBeforeTheLinkAndTheBusinessLogGoesOnAfterThem(@TempDir Path folder) throws Exception {
        SharedDatabase.resetForTheClass();
        SennardDatabaseConfig config = BusinessDb.config();
        Path decoyPassword = Files.writeString(folder.resolve("decoy.pw"), SENTINEL + "\n").toAbsolutePath();
        try (ToolServerSupport sennard = ToolServerSupport.startBeforeTheLink()) {
            PreLinkAdminRecords records = sennard.server.bean(PreLinkAdminRecords.class);
            assertEquals(StorePhase.NOT_CONFIGURED, sennard.server.bean(PreparedStore.class).status().phase());
            JsonNode before = sennard.state();
            assertTrue(before.path("link").path("beforeLink").asBoolean(), before.toString());
            assertTrue(before.path("link").path("saved").isNull(), "no link is saved yet: " + before.path("link"));
            assertEquals(0, before.path("keys").size());

            // The first key registers itself into the settings database.
            ToolServerSupport.Passkey owner = new ToolServerSupport.Passkey();
            sennard.sign(owner, owner.addChange(), true, null);
            assertEquals(1, sennard.state().path("keys").size());
            assertEquals(1, records.list().size());
            assertEquals("keys", records.list().getFirst().draft().target());
            PreLinkAdminRecord firstKeyRecord = records.list().getFirst();

            // What belongs to the business database waits for it.
            RawHttp.Response waits = sennard.post("/api/intent", ToolServerSupport.MAPPER.createObjectNode().<ObjectNode>set("change", ToolServerSupport.roleAdd("role-1", "Reviewer")).put("bootstrap", false));
            assertEquals(400, waits.status(), waits.text());
            assertEquals("ADMIN_CHANGE_INVALID", code(waits));
            assertEquals("BUSINESS_DATABASE_NOT_READY", reason(waits));

            // A link that carries the password, or that no store could use, is refused before the passkey is asked (p13).
            Path driver = config.driverPath().toAbsolutePath();
            RawHttp.Response carrying = sennard.post("/api/intent", ToolServerSupport.MAPPER.createObjectNode()
                    .<ObjectNode>set("change", link(driver, "jdbc:postgresql://127.0.0.1:1/none?password=" + SENTINEL, "u", decoyPassword, "LOOPBACK_PLAINTEXT")).put("bootstrap", false));
            assertEquals(400, carrying.status(), carrying.text());
            assertEquals("DATABASE_LINK_INVALID", reason(carrying));
            for (String otherForm : new String[] {"jdbc:db2://127.0.0.1:1/none:password=" + SENTINEL + ";", "jdbc:oracle:thin:scott/" + SENTINEL + "@//127.0.0.1:1/none"}) {
                RawHttp.Response refused = sennard.post("/api/intent", ToolServerSupport.MAPPER.createObjectNode()
                        .<ObjectNode>set("change", link(driver, otherForm, "u", decoyPassword, "LOOPBACK_PLAINTEXT")).put("bootstrap", false));
                assertEquals(400, refused.status(), refused.text());
                assertEquals("DATABASE_LINK_INVALID", reason(refused), otherForm);
            }
            RawHttp.Response h2 = sennard.post("/api/intent", ToolServerSupport.MAPPER.createObjectNode()
                    .<ObjectNode>set("change", link(driver, "jdbc:h2:mem:x", "u", decoyPassword, "LOOPBACK_PLAINTEXT")).put("bootstrap", false));
            assertEquals(400, h2.status(), "H2 is no business database: " + h2.text());

            // A first link that cannot connect (nothing listens on port 1): the store tries and fails, and the password file named is a decoy with a value to look for.
            sennard.sign(owner, link(driver, "jdbc:postgresql://127.0.0.1:1/none", "decoy", decoyPassword, "LOOPBACK_PLAINTEXT"));
            assertEquals(2, records.list().size());
            awaitPhase(sennard, StorePhase.FAILED);
            JsonNode failed = sennard.state();
            assertTrue(failed.path("link").path("beforeLink").asBoolean(), "never ready: the page is still the settings database's");
            assertEquals("FAILED", failed.path("link").path("phase").asString());
            assertEquals("jdbc:postgresql://127.0.0.1:1/none", failed.path("link").path("saved").path("jdbcUrl").asString());

            // The link is signed again with the database that exists, on the same chain (the head is the first link's record).
            sennard.sign(owner, link(config));
            assertEquals(3, records.list().size());
            awaitPhase(sennard, StorePhase.READY);

            // p13: the password is in the file and nowhere else.
            assertEquals(0, cellsHolding(sennard, SENTINEL), "no record before the link, no link setting holds the password");
            assertFalse(sennard.state().toString().contains(SENTINEL));
            assertFalse(sennard.state().path("link").has("password"));

            // p8: the page is the business database's now; the log goes on after the records made before it.
            JsonNode after = sennard.state();
            assertFalse(after.path("link").path("beforeLink").asBoolean(), after.toString());
            assertEquals("READY", after.path("link").path("phase").asString());
            assertEquals(1, after.path("keys").size());
            assertEquals(0, after.path("failures").size(), "the whole log verifies: " + after.path("failures"));
            // The personal edition has one passkey: another one is refused, here too, after the link.
            RawHttp.Response further = sennard.post("/api/intent", ToolServerSupport.MAPPER.createObjectNode().<ObjectNode>set("change", new ToolServerSupport.Passkey().addChange()).put("bootstrap", false));
            assertEquals(400, further.status(), further.text());
            assertEquals("SINGLE_PASSKEY", reason(further));
            sennard.sign(owner, ToolServerSupport.roleAdd("role-1", "Reviewer"));
            JsonNode last = sennard.state();
            assertEquals(1, last.path("keys").size());
            assertEquals(1, last.path("roles").size());
            assertEquals(0, last.path("failures").size(), last.path("failures").toString());
            assertEquals(4, last.path("lastSeq").asLong(), "one sequence: three records before the link, then the one of the business log");
            assertEquals(firstKeyRecord.draft().recordHash(), records.list().stream().filter(record -> record.draft().target().equals("keys")).reduce((a, b) -> b).orElseThrow().draft().recordHash(),
                    "the one key's record is the last of the chain of keys before the link");
            assertEquals(1, SessionServerSupport.count(sennard.server, "SELECT count(*) FROM sennard.admin_log"), "only the records after the link are in the business log");
            assertEquals(0, SessionServerSupport.count(sennard.server, "SELECT count(*) FROM sennard.admin_log WHERE POSITION(? IN intent) > 0", SENTINEL));
            assertNotNull(sennard.server.bean(PreLinkAdminRecords.class));

            // p9: closed; nothing more is written before the link, and the link signed now goes to the business log and is used by the next start (DatabaseSwitchServerTest).
            assertTrue(records.closed());
            assertEquals(SettingsCode.PRE_LINK_CLOSED, assertThrows(SettingsException.class, () -> records.append(SettingsFixturesDraft.record())).code());
            sennard.sign(owner, link(config));
            assertEquals(3, records.list().size(), "the section before the link takes no record");
            assertEquals(2, SessionServerSupport.count(sennard.server, "SELECT count(*) FROM sennard.admin_log"), "the link is a record of the business log, after the role's one (the records before the link are not in it)");
            JsonNode relinked = sennard.state();
            assertEquals("READY", relinked.path("link").path("phase").asString(), "the running store is not swapped");
            assertTrue(relinked.path("link").path("restartRequired").asBoolean(), relinked.path("link").toString());
            assertEquals(0, relinked.path("failures").size(), "the whole log verifies: " + relinked.path("failures"));
        }
    }

    /**
     * p10: a {@code sennard.store.database.*} key in the settings file is no source of the business database (the link on the page is the one): the key does not name a database for
     * the store, and it does not lock the page, which links the database as it does without the key.
     */
    @Test
    void p10ADatabaseKeyInTheSettingsFileIsNoSourceAndDoesNotLockThePage() throws Exception {
        SharedDatabase.resetForTheClass();
        SennardDatabaseConfig config = BusinessDb.config();
        try (ToolServerSupport sennard = ToolServerSupport.startBeforeTheLink("--sennard.store.database.url=jdbc:postgresql://127.0.0.1:1/none", "--sennard.store.database.user=" + config.ownerUser(),
                "--sennard.store.database.password-file=" + config.ownerPasswordRef(), "--sennard.store.database.driver-path=" + config.driverPath())) {
            assertEquals(StorePhase.NOT_CONFIGURED, sennard.server.bean(PreparedStore.class).status().phase(), "the key names no database for the store");
            JsonNode state = sennard.state();
            assertTrue(state.path("link").path("beforeLink").asBoolean(), state.toString());
            assertTrue(state.path("link").path("saved").isNull(), "the key is no saved link: " + state.path("link"));

            ToolServerSupport.Passkey owner = new ToolServerSupport.Passkey();
            sennard.sign(owner, owner.addChange(), true, null);
            sennard.sign(owner, link(config));
            awaitPhase(sennard, StorePhase.READY);
            assertEquals(config.jdbcUrl(), sennard.state().path("link").path("saved").path("jdbcUrl").asString(), "the page linked the database the key did not name");
            assertEquals(2, sennard.server.bean(PreLinkAdminRecords.class).list().size());
        }
    }

    /** A draft for a record the closed section must refuse. */
    private static final class SettingsFixturesDraft {
        static io.github.jaeseongs95.sennard.service.settings.PreLinkAdminDraft record() {
            return new io.github.jaeseongs95.sennard.service.settings.PreLinkAdminDraft("global", "{}", "cred", "a", "c", "s", "hash-" + UUID.randomUUID(), null);
        }
    }
}
