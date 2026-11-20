// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.service.settings.DatabaseLink;
import io.github.jaeseongs95.sennard.service.settings.DatabaseLinkSettings;
import io.github.jaeseongs95.sennard.service.store.pg.BusinessDb;
import io.github.jaeseongs95.sennard.service.store.pg.PreparedStore;
import io.github.jaeseongs95.sennard.service.store.pg.SennardDatabaseConfig;
import io.github.jaeseongs95.sennard.service.store.pg.StorePhase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Changing the link of the business database on the admin page while the store is ready: the link is the one source of the business database (the settings database of the state
 * folder), the page changes it at any time with a passkey, and the store, which is prepared once in a process, uses it from the next start.
 *
 * <pre>
 * s1  ready, change   a link signed while the store is ready is kept in the settings database and recorded in the business log; the store stays ready; the page says that the link is
 *                     used after a restart and shows the new link; before the change it said no restart was needed
 * s2  unreachable     a link whose database cannot be reached is refused when the signature is committed: nothing is kept (the saved link, the log) and the store stays ready
 * </pre>
 */
@Tag("business-db")
class DatabaseSwitchServerTest {
    private static ObjectNode link(SennardDatabaseConfig config, String url) {
        return ToolServerSupport.MAPPER.createObjectNode().put("op", "DATABASE_LINK").put("driverPath", config.driverPath().toAbsolutePath().toString()).put("jdbcUrl", url)
                .put("accountName", config.ownerUser()).put("passwordRef", config.ownerPasswordRef().toAbsolutePath().toString()).put("tls", config.tlsPolicy().name());
    }

    private static long businessLogRecords(ToolServerSupport sennard) {
        return SessionServerSupport.count(sennard.server, "SELECT count(*) FROM sennard.admin_log");
    }

    @Test
    void s1ALinkSignedWhileTheStoreIsReadyIsKeptRecordedAndUsedAfterARestart() throws Exception {
        // The log is the business database's: a server of its own starts on a database made anew, as every test of a class whose servers each register their own first key.
        SharedDatabase.resetForTheTest();
        SennardDatabaseConfig config = BusinessDb.config();
        try (ToolServerSupport sennard = ToolServerSupport.start()) {
            ToolServerSupport.Passkey owner = sennard.owner();
            DatabaseLinkSettings links = sennard.server.bean(DatabaseLinkSettings.class);
            DatabaseLink before = links.find().orElseThrow();
            JsonNode ready = sennard.state().path("link");
            assertEquals("READY", ready.path("phase").asString());
            assertFalse(ready.path("restartRequired").asBoolean(), "the saved link is the one the store started with: " + ready);
            long records = businessLogRecords(sennard);

            // The same database under another spelling of its URL: a link that connects, so what changes is only what the page keeps.
            String changed = config.jdbcUrl() + (config.jdbcUrl().contains("?") ? "&" : "?") + "ApplicationName=sennard-switch";
            sennard.sign(owner, link(config, changed));

            assertEquals(changed, links.find().orElseThrow().request().jdbcUrl(), "the link is kept in the settings database, the one source");
            assertNotEquals(before.request().jdbcUrl(), changed);
            assertEquals(records + 1, businessLogRecords(sennard), "the signature is a record of the business log");
            JsonNode after = sennard.state();
            assertEquals(0, after.path("failures").size(), "the whole log verifies: " + after.path("failures"));
            JsonNode view = after.path("link");
            assertEquals("READY", view.path("phase").asString(), "the running store is not swapped under its requests");
            assertEquals(StorePhase.READY, sennard.server.bean(PreparedStore.class).status().phase());
            assertEquals(changed, view.path("saved").path("jdbcUrl").asString(), "the page shows the link that is saved");
            assertTrue(view.path("restartRequired").asBoolean(), "the page says that the saved link is used after a restart: " + view);
        }
    }

    @Test
    void s2ALinkWhoseDatabaseCannotBeReachedIsRefusedAndNothingIsKept() throws Exception {
        SharedDatabase.resetForTheTest();
        SennardDatabaseConfig config = BusinessDb.config();
        try (ToolServerSupport sennard = ToolServerSupport.start()) {
            ToolServerSupport.Passkey owner = sennard.owner();
            DatabaseLinkSettings links = sennard.server.bean(DatabaseLinkSettings.class);
            DatabaseLink before = links.find().orElseThrow();
            long records = businessLogRecords(sennard);

            // Nothing listens on port 1: the link passes the check of its shape (a loopback address, plain text) and is refused when the database is tried.
            JsonNode prepared = sennard.prepare(link(config, "jdbc:postgresql://127.0.0.1:1/none"), false);
            String intent = prepared.path("intent").asString();
            RawHttp.Response refused = sennard.commit(intent, owner.assertion(intent, sennard.adminOrigin), UUID.randomUUID().toString(), null, null);

            assertNotEquals(200, refused.status(), refused.text());
            assertEquals(before, links.find().orElseThrow(), "the saved link is as it was");
            assertEquals(records, businessLogRecords(sennard), "the record rolled back with the refusal");
            JsonNode view = sennard.state().path("link");
            assertEquals("READY", view.path("phase").asString());
            assertFalse(view.path("restartRequired").asBoolean(), view.toString());
        }
    }
}
