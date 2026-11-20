// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.admincli.AdminReset;
import io.github.jaeseongs95.sennard.admincli.AdminResetContext;
import io.github.jaeseongs95.sennard.domain.error.GateCode;
import io.github.jaeseongs95.sennard.domain.error.GateRefusedException;
import io.github.jaeseongs95.sennard.service.settings.DatabaseLinkRequest;
import io.github.jaeseongs95.sennard.service.settings.DatabaseLinkSettings;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistry;

/**
 * {@code sennard admin-reset} against a running server with its real database: the signed log it reads is the one the admin page wrote, and what it archives
 * is what the page then no longer shows. Its server is this test's own, because the reset removes the only passkey.
 *
 * <p>The command is run through its entrance, {@link AdminResetContext}: its own Spring context reads the settings the way an installation gives them (the settings database of the
 * state folder the server of this test runs on, which holds the link of the business database) and opens the store the way the server does; the test makes no connection, no
 * transaction and no store of its own.
 */
@Tag("business-db")
class AdminResetTest {
    private static ToolServerSupport server;

    /**
     * The settings of the installation the server of this test runs on, as the command line arguments of the command's context. The passkey registry and the link of the business
     * database are the settings database of the state folder of the server of this test (the command's context is told the folder as an installation's would be).
     */
    private static String[] settings() {
        return new String[] {"--sennard.settings.directory=" + server.server.stateDirectory().resolve("settings")};
    }

    @BeforeAll
    static void start() throws Exception {
        server = ToolServerSupport.start();
        ToolServerSupport.Passkey key = server.owner();
        server.sign(key, ToolServerSupport.roleAdd("golden-role", "Golden role"));
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @Test
    void itSaysWhatItWouldArchiveAndOnlyWithConfirmationMovesTheLog(@TempDir Path home) throws IOException {
        AdminReset.Result dry = AdminResetContext.run(false, Clock.systemUTC(), home, settings());
        assertEquals(null, dry.reset());
        assertEquals(1, dry.summary().keys());
        assertEquals(1, dry.summary().roles());
        assertTrue(dry.summary().lastSeq() >= 2, dry.toString());
        assertEquals(1, server.state().path("keys").size(), "not confirmed: nothing moved");
        assertEquals(1, server.server.bean(PasskeyRegistry.class).list().size(), "not confirmed: the registry keeps its key");

        AdminReset.Result done = AdminResetContext.run(true, Clock.systemUTC(), home, settings());
        assertNotNull(done.reset());
        assertEquals(dry.summary().lastSeq(), done.reset().archived(), "every signed record is archived, none is deleted");
        assertEquals(0, server.state().path("keys").size(), "the page shows no passkey after the reset");
        assertTrue(server.server.bean(PasskeyRegistry.class).list().isEmpty(), "the reset empties the passkey registry too, so no key of the lost passkeys can sign");
        assertEquals(0, server.state().path("roles").size());
    }

    @Test
    void withoutTheDatabasePasswordFileItIsNotAttempted(@TempDir Path home) {
        DatabaseLinkSettings links = server.server.bean(DatabaseLinkSettings.class);
        DatabaseLinkRequest linked = links.find().orElseThrow().request();
        // The link of the server of this test names a password file that is not there. The settings folder is the server's, whose section of the log before the link is closed (its
        // business database was ready): the reset needs the database's own credential then. A new, empty settings folder would be an open section, which is reset without the
        // business database (AdminResetBeforeLinkServerTest).
        links.save(new DatabaseLinkRequest(linked.driverPath(), linked.jdbcUrl(), linked.accountName(), home.resolve("no-such-password").toAbsolutePath(), linked.tls()));
        try {
            GateRefusedException refused = assertThrows(GateRefusedException.class, () -> AdminResetContext.run(true, Clock.systemUTC(), home, settings()), "no credential, no reset");
            assertEquals(GateCode.ADMIN_RESET_NOT_PERMITTED, refused.code());
        } finally {
            links.save(linked);
        }
    }
}
