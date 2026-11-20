// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.jaeseongs95.sennard.admincli.AdminResetContext;
import io.github.jaeseongs95.sennard.service.settings.PasskeyAlgorithm;
import io.github.jaeseongs95.sennard.service.settings.PasskeyCredential;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistration;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistry;
import io.github.jaeseongs95.sennard.service.settings.PasskeyState;
import tools.jackson.databind.JsonNode;

/**
 * AGS3-D-0254: the passkey public keys and their revocation are the settings database's (the passkey registry), and the business database's signed log holds the key's
 * credential id and fingerprint only. Each server here is its own, because the first passkey registers on a server that has none.
 *
 * <pre>
 * k1  register, sign   the first key registers itself; the registry holds its bytes; a later change it signs is verified with that key
 * k2  no key bytes     no column of the business log's tables (the log and its archive) holds the public key; the registration record holds the fingerprint
 * k3  one passkey      the personal edition has one: another registration is refused while one is registered (SINGLE_PASSKEY), as a first registration too (ADMIN_ALREADY_BOOTSTRAPPED)
 * k4  last key         the only passkey cannot be revoked; the way back from a lost one is the admin reset
 * k5  swapped key      the registry's bytes for a registered credential changed (another fingerprint): the registration fails the log's check and nothing signed with it counts
 * k6  retried          a key the registry holds already (an earlier commit failed after it) is taken again; a commit without the key, or with another, registers nothing
 * k7  lost key row     the registry lost the row of a registered key (audit 3): the log fails its integrity, takes neither the first key nor a new signature, and the admin reset starts it again
 * k8  changed key      the registry's bytes of a registered key changed: the same
 * </pre>
 */
@Tag("business-db")
class PasskeyRegistryServerTest {
    /** Each test starts a server of its own with a registry of its own, so each starts on a business log of its own (see {@link SharedDatabase#resetForTheTest()}). */
    @BeforeEach
    void startFromAnEmptyBusinessDatabase() {
        SharedDatabase.resetForTheTest();
    }

    private static String code(RawHttp.Response response) {
        return SessionServerSupport.json(response).path("code").asString();
    }

    /** The number of cells of the log and its archive that hold this text (the standard POSITION, so the key's own characters are no pattern). */
    private static long cellsHolding(ToolServerSupport sennard, String text) {
        String position = "POSITION(? IN %s) > 0";
        StringBuilder where = new StringBuilder();
        java.util.List<Object> params = new java.util.ArrayList<>();
        for (String table : new String[] {"admin_log", "admin_log_archive"}) {
            for (String column : new String[] {"target", "intent", "credential_id", "authenticator_data", "client_data_json", "signature", "record_hash"}) {
                where.append(where.isEmpty() ? "" : " OR ").append(String.format(position, column));
                params.add(text);
            }
            // each table is counted on its own: the archive may be empty
            long found = SessionServerSupport.count(sennard.server, "SELECT count(*) FROM sennard." + table + " WHERE " + where, params.toArray());
            if (found > 0) {
                return found;
            }
            where.setLength(0);
            params.clear();
        }
        return 0;
    }

    @Test
    void k1k2TheFirstKeyRegistersInTheRegistryAndSignsWithItsKeyAndTheBusinessLogHoldsNoKeyBytes() throws Exception {
        try (ToolServerSupport sennard = ToolServerSupport.start()) {
            PasskeyRegistry registry = sennard.server.bean(PasskeyRegistry.class);
            ToolServerSupport.Passkey owner = sennard.owner();

            PasskeyCredential held = registry.find(owner.credentialId).orElseThrow();
            assertEquals(PasskeyState.ACTIVE, held.state());
            assertEquals(owner.publicKey(), held.registration().publicKey(), "the registry holds the key's bytes");
            assertEquals(PasskeyAlgorithm.ES256, held.registration().algorithm());

            sennard.sign(owner, ToolServerSupport.roleAdd("role-1", "Reviewer"));
            assertEquals(1, sennard.state().path("roles").size(), "a change signed with the registry's key is verified and applied");
            assertEquals(0, sennard.state().path("failures").size());

            assertEquals(0, cellsHolding(sennard, owner.publicKey()), "no public key bytes in the business database's log or its archive");
            assertEquals(1, SessionServerSupport.count(sennard.server, "SELECT count(*) FROM sennard.admin_log WHERE target = 'keys' AND POSITION(? IN intent) > 0", owner.fingerprint()),
                    "the registration record names the key by its fingerprint");
            assertEquals(1, SessionServerSupport.count(sennard.server, "SELECT count(*) FROM sennard.admin_log WHERE target = 'keys' AND POSITION(? IN intent) > 0", owner.credentialId));
        }
    }

    @Test
    void k3AnotherPasskeyIsRefusedWhileOneIsRegisteredWhoeverAsksAndTheRegistryKeepsOne() throws Exception {
        try (ToolServerSupport sennard = ToolServerSupport.start()) {
            PasskeyRegistry registry = sennard.server.bean(PasskeyRegistry.class);
            ToolServerSupport.Passkey owner = sennard.owner();
            ToolServerSupport.Passkey second = new ToolServerSupport.Passkey();

            // Signed by the passkey that is registered: the way a further key used to be added.
            RawHttp.Response further = sennard.post("/api/intent", ToolServerSupport.MAPPER.createObjectNode().<tools.jackson.databind.node.ObjectNode>set("change", second.addChange()).put("bootstrap", false));
            assertEquals(400, further.status(), further.text());
            assertEquals("ADMIN_CHANGE_INVALID", code(further));
            assertEquals("SINGLE_PASSKEY", SessionServerSupport.json(further).path("details").path("reason").asString());

            // Asked as the first registration: the page says a key exists already.
            RawHttp.Response first = sennard.post("/api/intent", ToolServerSupport.MAPPER.createObjectNode().<tools.jackson.databind.node.ObjectNode>set("change", second.addChange()).put("bootstrap", true));
            assertEquals("ADMIN_ALREADY_BOOTSTRAPPED", code(first), first.text());

            assertEquals(1, registry.list().size());
            assertEquals(owner.credentialId, registry.list().getFirst().registration().credentialId());
            assertEquals(1, sennard.state().path("keys").size());
        }
    }

    @Test
    void k4TheOnlyPasskeyCannotBeRevoked() throws Exception {
        try (ToolServerSupport sennard = ToolServerSupport.start()) {
            PasskeyRegistry registry = sennard.server.bean(PasskeyRegistry.class);
            ToolServerSupport.Passkey owner = sennard.owner();
            RawHttp.Response refused = sennard.post("/api/intent", ToolServerSupport.MAPPER.createObjectNode().<tools.jackson.databind.node.ObjectNode>set("change", ToolServerSupport.revokeChange(owner)).put("bootstrap", false));
            assertEquals("ADMIN_LAST_KEY_REVOKE_REFUSED", code(refused), refused.text());
            assertEquals(PasskeyState.ACTIVE, registry.find(owner.credentialId).orElseThrow().state());
            // The way back from a lost passkey is the admin reset; the signing still works with the passkey that is registered.
            sennard.sign(owner, ToolServerSupport.roleAdd("role-1", "Reviewer"));
            assertEquals(1, sennard.state().path("roles").size());
        }
    }

    @Test
    void k5AKeyOfTheRegistryThatIsNotTheFingerprintOfTheSignedRecordFailsTheLog() throws Exception {
        try (ToolServerSupport sennard = ToolServerSupport.start()) {
            ToolServerSupport.Passkey owner = sennard.owner();
            sennard.sign(owner, ToolServerSupport.roleAdd("role-1", "Reviewer"));
            assertEquals(1, sennard.state().path("keys").size());

            // Someone changed the settings database: the credential id the log registered now has the bytes of another key.
            ToolServerSupport.Passkey planted = new ToolServerSupport.Passkey();
            sennard.server.context.getBean("settingsJdbcClient", JdbcClient.class).sql("UPDATE sennard.passkey_credentials SET public_key = ? WHERE credential_id = ?")
                    .params(planted.publicKey(), owner.credentialId).update();

            JsonNode state = sennard.state();
            assertEquals(0, state.path("keys").size(), "the key whose registration fails is not registered");
            assertEquals(0, state.path("roles").size(), "nothing the key signed is applied");
            assertFalse(state.path("failures").isEmpty());
            assertEquals("ADMIN_RECORD_ALTERED", state.path("failures").get(0).path("code").asString(), state.path("failures").toString());
        }
    }

    @Test
    void k6AKeyTheRegistryHoldsAlreadyIsTakenAgainAndACommitWithoutItsKeyRegistersNothing() throws Exception {
        try (ToolServerSupport sennard = ToolServerSupport.start()) {
            PasskeyRegistry registry = sennard.server.bean(PasskeyRegistry.class);
            ToolServerSupport.Passkey owner = new ToolServerSupport.Passkey();
            ToolServerSupport.Passkey other = new ToolServerSupport.Passkey();

            // A commit with no key, or with another key than the fingerprint names, is refused and registers nothing.
            JsonNode prepared = sennard.prepare(owner.addChange(), true);
            String intent = prepared.path("intent").asString();
            RawHttp.Response without = sennard.commit(intent, owner.assertion(intent, sennard.adminOrigin), UUID.randomUUID().toString(), null, null);
            assertEquals(400, without.status(), without.text());
            assertEquals("ADMIN_CHANGE_INVALID", code(without));
            RawHttp.Response wrong = sennard.commit(intent, owner.assertion(intent, sennard.adminOrigin), UUID.randomUUID().toString(), null, null, other.publicKey());
            assertEquals(400, wrong.status(), wrong.text());
            assertEquals("ADMIN_CHANGE_INVALID", code(wrong));
            assertTrue(registry.list().isEmpty(), "a refused registration leaves no key behind");

            // An earlier commit registered the key and then failed to record it: the key is there, and the same registration is taken.
            registry.register(new PasskeyRegistration(owner.credentialId, owner.publicKey(), PasskeyAlgorithm.ES256, "left by a failed commit"));
            assertEquals(0, sennard.state().path("keys").size(), "a key with no record in the log is no registered key");
            RawHttp.Response taken = sennard.commit(intent, owner.assertion(intent, sennard.adminOrigin), UUID.randomUUID().toString(), null, null, owner.publicKey());
            assertEquals(200, taken.status(), taken.text());
            assertEquals(1, registry.list().size());
            assertEquals(1, sennard.state().path("keys").size());
            assertNotEquals("", registry.find(owner.credentialId).orElseThrow().registration().label());
        }
    }

    /** The settings of this test's server as the admin reset command is given them: the server's settings folder, which holds the link of the installation's business database. */
    private static String[] resetSettings(ToolServerSupport sennard) {
        return new String[] {"--sennard.settings.directory=" + sennard.server.stateDirectory().resolve("settings")};
    }

    /**
     * What a log that failed its integrity does (audit 3): the page shows the failure, the first key is refused (it would otherwise register itself on a log whose key is gone), a
     * listed key signs nothing new, and the admin reset is the way out, after which the first key registers again.
     */
    private static void theLogThatFailedItsIntegrityTakesNoKeyAndNoSignatureUntilTheReset(ToolServerSupport sennard, Path home) throws Exception {
        JsonNode state = sennard.state();
        assertEquals(0, state.path("keys").size(), "the key whose registration fails is not registered");
        assertEquals("ADMIN_RECORD_ALTERED", state.path("failures").get(0).path("code").asString(), state.path("failures").toString());

        RawHttp.Response firstKey = sennard.post("/api/intent", ToolServerSupport.MAPPER.createObjectNode()
                .<tools.jackson.databind.node.ObjectNode>set("change", new ToolServerSupport.Passkey().addChange()).put("bootstrap", true));
        assertEquals(400, firstKey.status(), "no first key on a log that failed its integrity: " + firstKey.text());
        assertEquals("ADMIN_RECORD_ALTERED", code(firstKey));
        RawHttp.Response signature = sennard.post("/api/intent", ToolServerSupport.MAPPER.createObjectNode()
                .<tools.jackson.databind.node.ObjectNode>set("change", ToolServerSupport.roleAdd("role-2", "After")).put("bootstrap", false));
        assertEquals(400, signature.status(), "no new signature either: " + signature.text());
        assertEquals("ADMIN_RECORD_ALTERED", code(signature));

        AdminResetContext.run(true, Clock.systemUTC(), home, resetSettings(sennard));
        assertEquals(0, sennard.state().path("failures").size(), "the reset archived the log that failed");
        ToolServerSupport.Passkey next = new ToolServerSupport.Passkey();
        sennard.sign(next, next.addChange(), true, null);
        assertEquals(1, sennard.state().path("keys").size(), "after the reset the first key registers again");
    }

    @Test
    void k7TheRegistryLostTheRowOfARegisteredKeyAndTheLogFailsItsIntegrity(@TempDir Path home) throws Exception {
        try (ToolServerSupport sennard = ToolServerSupport.start()) {
            ToolServerSupport.Passkey owner = sennard.owner();
            sennard.sign(owner, ToolServerSupport.roleAdd("role-1", "Reviewer"));
            sennard.server.context.getBean("settingsJdbcClient", JdbcClient.class).sql("DELETE FROM sennard.passkey_credentials WHERE credential_id = ?").param(owner.credentialId).update();
            theLogThatFailedItsIntegrityTakesNoKeyAndNoSignatureUntilTheReset(sennard, home);
        }
    }

    @Test
    void k8TheRegistryChangedTheBytesOfARegisteredKeyAndTheLogFailsItsIntegrity(@TempDir Path home) throws Exception {
        try (ToolServerSupport sennard = ToolServerSupport.start()) {
            ToolServerSupport.Passkey owner = sennard.owner();
            sennard.sign(owner, ToolServerSupport.roleAdd("role-1", "Reviewer"));
            sennard.server.context.getBean("settingsJdbcClient", JdbcClient.class).sql("UPDATE sennard.passkey_credentials SET public_key = ? WHERE credential_id = ?")
                    .params(new ToolServerSupport.Passkey().publicKey(), owner.credentialId).update();
            theLogThatFailedItsIntegrityTakesNoKeyAndNoSignatureUntilTheReset(sennard, home);
        }
    }
}
