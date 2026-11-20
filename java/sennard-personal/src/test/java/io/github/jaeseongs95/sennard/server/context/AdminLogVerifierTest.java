// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.GeneralSecurityException;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.service.settings.PasskeyAlgorithm;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistration;
import io.github.jaeseongs95.sennard.service.store.modules.ContextLayerStore.LogRecord;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reading and folding the signed log: an intent is read once from the exact canonical text, and a record that fails is reported with the reason and never
 * applied.
 *
 * <pre>
 * i1  intent text   a canonical intent is read back as the record that wrote it; text that is not canonical (spaces, another key order, another number form) is refused
 * i2  intent        a wrong version, domain, relying party, time, a key more or fewer, a null where text goes is non-canonical; a fault inside the change is an invalid change
 * i3  target        the intent's target must be the chain its change is appended to
 * f1  fold          a signed chain folds to the keys, the global context and what the labels name; the same on the path without a Spring context
 * f2  failures      a record altered after its hash, a record on a moved chain head and a record whose intent cannot be read are each reported with their reason
 * </pre>
 */
class AdminLogVerifierTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    /** The keys the signatures of these tests are checked with: a test registers its key here before it folds (every key is new, so the tests share the registry). */
    private static final InMemoryPasskeys PASSKEYS = new InMemoryPasskeys();
    private static final AdminLogVerifier VERIFIER = AdminOpTest.verifier(PASSKEYS);
    private static final String ISSUED_AT = "2026-11-11T00:00:00.000Z";

    private static String canonical(String json) {
        return CanonicalJson.of(MAPPER.readTree(json));
    }

    private static String intentText(String change, String target, String extra) {
        return canonical("{\"v\":1,\"domain\":\"sennard.admin\",\"rpId\":\"localhost\",\"target\":\"" + target + "\",\"prevTargetHash\":null,\"change\":" + change + ",\"issuedAt\":\"" + ISSUED_AT + "\"" + extra
                + "}");
    }

    private static SennardException refusedIntent(String text) {
        return assertThrows(SennardException.class, () -> VERIFIER.parseIntent(text), text);
    }

    /** I1 */
    @Test
    void aCanonicalIntentIsReadBackAndTextThatIsNotCanonicalIsRefused() {
        String global = "{\"op\":\"GLOBAL_SET\",\"body\":\"x\"}";
        String text = intentText(global, "global", "");
        AdminLog.Intent intent = VERIFIER.parseIntent(text);
        assertEquals(new AdminLog.Intent(1, "sennard.admin", "localhost", "global", null, new AdminChange.GlobalSet("x"), ISSUED_AT), intent);
        assertEquals(text, VERIFIER.buildIntent(intent.change(), null, ISSUED_AT));
        for (String bad : new String[] {text.replace("{", "{ "), text.replace(",", ", "), text + " ", "\n" + text,
                "{\"v\":1,\"domain\":\"sennard.admin\",\"rpId\":\"localhost\",\"target\":\"global\",\"prevTargetHash\":null,\"change\":{\"op\":\"GLOBAL_SET\",\"body\":\"x\"},\"issuedAt\":\"" + ISSUED_AT + "\"}",
                text.replace("\"v\":1", "\"v\":1.0"), "", "{", "not json"}) {
            assertEquals("ADMIN_INTENT_NONCANONICAL", refusedIntent(bad).code().wireCode(), bad);
        }
    }

    /** I2 */
    @Test
    void aFaultOfTheIntentIsNonCanonicalAndAFaultOfItsChangeIsAnInvalidChange() {
        String global = "{\"op\":\"GLOBAL_SET\",\"body\":\"x\"}";
        String good = "{\"v\":1,\"domain\":\"sennard.admin\",\"rpId\":\"localhost\",\"target\":\"global\",\"prevTargetHash\":null,\"change\":" + global + ",\"issuedAt\":\"" + ISSUED_AT + "\"}";
        for (String bad : new String[] {good.replace("\"v\":1", "\"v\":2"), good.replace("\"v\":1", "\"v\":\"1\""), good.replace("\"v\":1", "\"v\":null"), good.replace("sennard.admin", "sennard.other"),
                good.replace("localhost", "example.org"), good.replace(ISSUED_AT, "yesterday"), good.replace(ISSUED_AT, "2026-11-11"), good.replace("\"issuedAt\":\"" + ISSUED_AT + "\"", "\"issuedAt\":5"),
                good.replace("\"target\":\"global\"", "\"target\":5"), good.replace("\"prevTargetHash\":null,", ""), good.replace("\"issuedAt\"", "\"extra\":1,\"issuedAt\""),
                good.replace("\"prevTargetHash\":null", "\"prevTargetHash\":5"), "[]", "null", "5"}) {
            assertEquals("ADMIN_INTENT_NONCANONICAL", refusedIntent(canonical(bad)).code().wireCode(), bad);
        }
        for (String bad : new String[] {good.replace("GLOBAL_SET", "NOPE"), good.replace("\"body\":\"x\"", "\"body\":5"), good.replace("\"body\":\"x\"", "\"body\":\"x\",\"extra\":1"),
                good.replace(",\"body\":\"x\"", ""), good.replace("\"op\":\"GLOBAL_SET\",", ""), good.replace("\"change\":" + global, "\"change\":5")}) {
            assertEquals("ADMIN_CHANGE_INVALID", refusedIntent(canonical(bad)).code().wireCode(), bad);
        }
    }

    /** I3 */
    @Test
    void theTargetOfAnIntentIsTheChainOfItsChange() {
        SennardException wrong = refusedIntent(intentText("{\"op\":\"GLOBAL_SET\",\"body\":\"x\"}", "roles", ""));
        assertEquals("ADMIN_CHANGE_INVALID", wrong.code().wireCode());
        assertEquals("roles", wrong.details().get("target"));
        assertEquals("roleBody:r-1", VERIFIER.parseIntent(intentText("{\"op\":\"ROLE_BODY_SET\",\"roleId\":\"r-1\",\"body\":\"\",\"expectedStateToken\":null}", "roleBody:r-1", "")).target());
    }

    private record Signed(LogRecord record, String intent) {
    }

    /** A record of the log: this change on this chain head, signed by the key, stored as sequence number {@code seq}. */
    private static Signed signed(AdminPasskey key, AdminChange change, String prev, long seq) throws GeneralSecurityException {
        String intent = VERIFIER.buildIntent(change, prev, ISSUED_AT);
        AdminWebAuthn.Assertion assertion = key.assertion(intent);
        String hash = VERIFIER.recordHash(intent, assertion.credentialId(), assertion.authenticatorData(), assertion.clientDataJson(), assertion.signature());
        return new Signed(new LogRecord(seq, AdminLog.changeTarget(change), intent, assertion.credentialId(), assertion.authenticatorData(), assertion.clientDataJson(), assertion.signature(), hash,
                ISSUED_AT), intent);
    }

    /** F1 */
    @Test
    void aSignedChainFoldsToWhatItSaysEvenWithoutASpringContext() throws GeneralSecurityException {
        AdminPasskey key = new AdminPasskey();
        PASSKEYS.register(key.registration("my key"));
        Signed first = signed(key, key.keyAdd("my key"), null, 1);
        Signed second = signed(key, new AdminChange.GlobalSet("hello"), null, 2);
        Signed third = signed(key, new AdminChange.GlobalSet("hello again"), second.record().recordHash(), 3);
        Signed role = signed(key, new AdminChange.RoleAdd("role-1", "Reviewer"), null, 4);
        List<LogRecord> records = List.of(first.record(), second.record(), third.record(), role.record());
        for (AdminLog.State state : new AdminLog.State[] {VERIFIER.fold(records), AdminLog.fold(records, MAPPER, PASSKEYS)}) {
            assertTrue(state.failures.isEmpty(), state.failures.toString());
            assertEquals(4, state.lastSeq);
            assertEquals(List.of(key.credentialId), List.copyOf(state.keys.keySet()));
            assertEquals("my key", state.keyLabels.get(key.credentialId));
            assertEquals(key.credentialId, state.bootstrap.credentialId());
            assertEquals("hello again", state.global.body());
            assertEquals(List.of("hello again", "hello"), state.globalHistory.stream().map(AdminLog.GlobalState::body).toList());
            assertEquals("Reviewer", state.roles.get("role-1").name());
            assertEquals(third.record().recordHash(), state.heads.get("global").recordHash());
            assertEquals(ISSUED_AT, state.keyLastUsed.get(key.credentialId));
        }
    }

    /**
     * AGS3-D-0143: every hook that reads the shared context folds the whole log, and a fold verified every signature again. An unchanged record's signature is verified
     * once; a record added later is the only new check.
     */
    @Test
    void foldingALogAgainChecksOnlyTheSignaturesNotCheckedBefore() throws GeneralSecurityException {
        AdminLogVerifier verifier = AdminOpTest.verifier(PASSKEYS);
        AdminPasskey key = new AdminPasskey();
        PASSKEYS.register(key.registration("my key"));
        Signed first = signed(key, key.keyAdd("my key"), null, 1);
        Signed second = signed(key, new AdminChange.GlobalSet("hello"), null, 2);
        Signed third = signed(key, new AdminChange.GlobalSet("hello again"), second.record().recordHash(), 3);

        AdminLog.State once = verifier.fold(List.of(first.record(), second.record()));
        assertEquals(2, verifier.signatureChecks());
        AdminLog.State again = verifier.fold(List.of(first.record(), second.record()));
        assertEquals(2, verifier.signatureChecks(), "the same two records are not verified again");
        assertEquals(once.global.body(), again.global.body());
        assertTrue(again.failures.isEmpty(), again.failures.toString());

        AdminLog.State grown = verifier.fold(List.of(first.record(), second.record(), third.record()));
        assertEquals(3, verifier.signatureChecks(), "only the added record is verified");
        assertEquals("hello again", grown.global.body());
    }

    /** A signature that failed is not remembered: it is refused every time it is folded. */
    @Test
    void aSignatureThatFailsIsCheckedAndRefusedEveryTime() throws GeneralSecurityException {
        AdminLogVerifier verifier = AdminOpTest.verifier(PASSKEYS);
        AdminPasskey key = new AdminPasskey();
        PASSKEYS.register(key.registration("my key"));
        Signed first = signed(key, key.keyAdd("my key"), null, 1);
        Signed second = signed(key, new AdminChange.GlobalSet("hello"), null, 2);
        Signed other = signed(key, new AdminChange.GlobalSet("other"), null, 3);
        // The signature of another intent under a hash that covers it: the hash holds, the signature does not.
        LogRecord forged = new LogRecord(2, "global", second.record().intent(), key.credentialId, other.record().authenticatorData(), other.record().clientDataJson(), other.record().signature(),
                verifier.recordHash(second.record().intent(), key.credentialId, other.record().authenticatorData(), other.record().clientDataJson(), other.record().signature()), ISSUED_AT);

        for (int round = 1; round <= 2; round++) {
            AdminLog.State state = verifier.fold(List.of(first.record(), forged));
            assertEquals(1, state.failures.size(), state.failures.toString());
            assertNull(state.global, "the forged record was not applied");
            assertEquals(1 + round, verifier.signatureChecks(), "round " + round + ": the key record once, the forged one each time");
        }
    }

    /** F2 */
    @Test
    void aRecordThatFailsIsReportedWithItsReasonAndNeverApplied() throws GeneralSecurityException {
        AdminPasskey key = new AdminPasskey();
        PASSKEYS.register(key.registration("my key"));
        Signed first = signed(key, key.keyAdd("my key"), null, 1);
        Signed second = signed(key, new AdminChange.GlobalSet("hello"), null, 2);
        // Altered after its hash was made: another body in the stored intent.
        String otherIntent = VERIFIER.buildIntent(new AdminChange.GlobalSet("changed"), second.record().recordHash(), ISSUED_AT);
        LogRecord altered = new LogRecord(3, "global", otherIntent, second.record().credentialId(), second.record().authenticatorData(), second.record().clientDataJson(), second.record().signature(),
                signed(key, new AdminChange.GlobalSet("changed"), second.record().recordHash(), 3).record().recordHash(), ISSUED_AT);
        // Signed for a chain head that is not the head: it starts the chain again.
        Signed stale = signed(key, new AdminChange.GlobalSet("again from nothing"), null, 4);
        // Valid JSON in canonical form, but no intent.
        String nothing = "{}";
        LogRecord unreadable = new LogRecord(5, "global", nothing, key.credentialId, second.record().authenticatorData(), second.record().clientDataJson(), second.record().signature(),
                VERIFIER.recordHash(nothing, key.credentialId, second.record().authenticatorData(), second.record().clientDataJson(), second.record().signature()), ISSUED_AT);
        // A change that is no kind: its change is shown as nothing, its reason is the change.
        String noKind = intentText("{\"op\":\"NOPE\"}", "global", "");
        LogRecord noKindRecord = new LogRecord(6, "global", noKind, key.credentialId, second.record().authenticatorData(), second.record().clientDataJson(), second.record().signature(),
                VERIFIER.recordHash(noKind, key.credentialId, second.record().authenticatorData(), second.record().clientDataJson(), second.record().signature()), ISSUED_AT);

        AdminLog.State state = VERIFIER.fold(List.of(first.record(), second.record(), altered, stale.record(), unreadable, noKindRecord));
        assertEquals("hello", state.global.body(), "none of the failed records was applied");
        assertEquals(List.of("ADMIN_RECORD_ALTERED", "ADMIN_TARGET_STALE", "ADMIN_INTENT_NONCANONICAL", "ADMIN_CHANGE_INVALID"), state.failures.stream().map(AdminLog.Failure::code).toList());
        assertEquals(new AdminChange.GlobalSet("changed"), state.failures.get(0).change(), "an altered record still shows the change it carries");
        assertEquals(new AdminChange.GlobalSet("again from nothing"), state.failures.get(1).change());
        assertNull(state.failures.get(2).change(), "an intent that cannot be read has no change to show");
        assertNull(state.failures.get(3).change());
        assertEquals(List.of(3L, 4L, 5L, 6L), state.failures.stream().map(AdminLog.Failure::seq).toList());
    }

    /**
     * R1 (AGS3-D-0254): a registration in the log names its key by the fingerprint, and the stored record has no public key bytes anywhere; the signature is checked with the key
     * of the registry.
     */
    @Test
    void aRegistrationRecordHoldsTheFingerprintOfTheKeyAndNotTheKey() throws GeneralSecurityException {
        AdminPasskey key = new AdminPasskey();
        PASSKEYS.register(key.registration("my key"));
        LogRecord record = signed(key, key.keyAdd("my key"), null, 1).record();
        String everything = String.join("|", record.target(), record.intent(), record.credentialId(), record.authenticatorData(), record.clientDataJson(), record.signature(), record.recordHash());
        assertFalse(everything.contains(key.publicKey()), "no public key bytes in the record");
        assertTrue(record.intent().contains("\"fingerprint\":\"" + key.fingerprint() + "\""), record.intent());
        assertTrue(record.intent().contains("\"credentialId\":\"" + key.credentialId + "\""), record.intent());
        AdminLog.State state = VERIFIER.fold(List.of(record));
        assertTrue(state.failures.isEmpty(), state.failures.toString());
        assertEquals(key.fingerprint(), state.keys.get(key.credentialId).fingerprint());
        assertEquals(AdminWebAuthn.displayFingerprint(key.fingerprint()), state.bootstrap.fingerprint());
    }

    /** R2: a record signed before its key was revoked is checked with that key's bytes again at every fold; a new signature needs the key to be active in the registry. */
    @Test
    void aRevokedKeyStillVerifiesItsOldRecordsAndCannotSignANewOne() throws GeneralSecurityException {
        InMemoryPasskeys passkeys = new InMemoryPasskeys();
        AdminLogVerifier verifier = AdminOpTest.verifier(passkeys);
        AdminPasskey key = new AdminPasskey();
        passkeys.register(key.registration("my key"));
        List<LogRecord> records = List.of(signed(key, key.keyAdd("my key"), null, 1).record(), signed(key, new AdminChange.GlobalSet("hello"), null, 2).record());
        verifier.requireActive(verifier.keys(), key.credentialId);

        passkeys.revoke(key.credentialId);
        AdminLog.State state = verifier.fold(records);
        assertTrue(state.failures.isEmpty(), state.failures.toString());
        assertEquals("hello", state.global.body(), "the old records still count");
        assertEquals("ADMIN_KEY_REVOKED", assertThrows(SennardException.class, () -> verifier.requireActive(verifier.keys(), key.credentialId)).code().wireCode());
        assertEquals("ADMIN_KEY_UNKNOWN", assertThrows(SennardException.class, () -> verifier.requireActive(verifier.keys(), "never-registered")).code().wireCode());
    }

    /**
     * R3: a key the log names that the registry does not hold is an integrity failure (not an unknown key); the record is reported and nothing of it is applied, and a log that folded to
     * no key because of it takes no first key (the first key would otherwise register itself on a log whose registry was lost).
     */
    @Test
    void aKeyTheRegistryLacksFailsItsRecords() throws GeneralSecurityException {
        AdminLogVerifier verifier = AdminOpTest.verifier(new InMemoryPasskeys());
        AdminPasskey key = new AdminPasskey();
        AdminLog.State state = verifier.fold(List.of(signed(key, key.keyAdd("my key"), null, 1).record(), signed(key, new AdminChange.GlobalSet("hello"), null, 2).record()));
        assertEquals("ADMIN_RECORD_ALTERED", state.failures.get(0).code(), state.failures.toString());
        assertTrue(state.keys.isEmpty());
        assertNull(state.global);
        SennardException refused = assertThrows(SennardException.class, () -> AdminLog.requireIntact(state), "no first key on a log that failed its integrity");
        assertEquals("ADMIN_RECORD_ALTERED", refused.code().wireCode());
        assertEquals("LOG_INTEGRITY_FAILED", refused.details().get("reason"));
    }

    /** R3b: the key a record registers is held to the registry as the signer is: a second key that never signs, and whose registry row is gone, fails the record that registers it. */
    @Test
    void aSecondKeyTheRegistryLacksFailsTheRecordThatRegistersItAndStopsNewSignatures() throws GeneralSecurityException {
        InMemoryPasskeys registry = new InMemoryPasskeys();
        AdminLogVerifier checking = AdminOpTest.verifier(registry);
        AdminPasskey first = new AdminPasskey();
        AdminPasskey second = new AdminPasskey();
        registry.register(first.registration("first"));
        Signed one = signed(first, first.keyAdd("first"), null, 1);
        Signed two = signed(first, second.keyAdd("second"), one.record().recordHash(), 2);
        AdminLog.State lost = checking.fold(List.of(one.record(), two.record()));
        assertEquals(List.of("ADMIN_RECORD_ALTERED"), lost.failures.stream().map(AdminLog.Failure::code).toList(), lost.failures.toString());
        assertEquals(1, lost.keys.size(), "the first key is registered, the second is not");
        assertEquals("LOG_INTEGRITY_FAILED", assertThrows(SennardException.class, () -> AdminLog.requireIntact(lost), "a listed key signs no more either").details().get("reason"));

        registry.register(second.registration("second"));
        AdminLog.State kept = checking.fold(List.of(one.record(), two.record()));
        assertTrue(kept.failures.isEmpty(), kept.failures.toString());
        AdminLog.requireIntact(kept);
    }

    /** R3c: a failed record that is not an integrity failure (a stale chain head) stops no signature of a listed key: the user signs again. */
    @Test
    void aStaleChainHeadStopsNoSignatureOfAListedKey() throws GeneralSecurityException {
        InMemoryPasskeys registry = new InMemoryPasskeys();
        AdminLogVerifier checking = AdminOpTest.verifier(registry);
        AdminPasskey key = new AdminPasskey();
        registry.register(key.registration("key"));
        Signed add = signed(key, key.keyAdd("key"), null, 1);
        Signed stale = signed(key, new AdminChange.GlobalSet("x"), "sha256:not-the-head", 2);
        AdminLog.State state = checking.fold(List.of(add.record(), stale.record()));
        assertEquals(List.of("ADMIN_TARGET_STALE"), state.failures.stream().map(AdminLog.Failure::code).toList());
        AdminLog.requireIntact(state);
    }

    /** R4: the key the registry holds is not the key the signed record names (the settings database was changed): the record fails, though the new key would verify the signature. */
    @Test
    void aKeyOfTheRegistryThatIsNotTheFingerprintOfTheRecordFailsIt() throws GeneralSecurityException {
        InMemoryPasskeys passkeys = new InMemoryPasskeys();
        AdminLogVerifier verifier = AdminOpTest.verifier(passkeys);
        AdminPasskey key = new AdminPasskey();
        AdminPasskey planted = new AdminPasskey();
        // The registry holds the planted key's bytes under the credential id the log registered.
        passkeys.register(new PasskeyRegistration(key.credentialId, planted.publicKey(), PasskeyAlgorithm.ES256, "planted"));
        AdminLog.State state = verifier.fold(List.of(signed(key, key.keyAdd("my key"), null, 1).record(), signed(key, new AdminChange.GlobalSet("hello"), null, 2).record()));
        assertEquals("ADMIN_RECORD_ALTERED", state.failures.get(0).code(), state.failures.toString());
        assertTrue(state.keys.isEmpty(), "the key was not registered");
        assertNull(state.global, "nothing signed with it was applied");

        // The same bytes under another algorithm are not the key the record names either.
        InMemoryPasskeys other = new InMemoryPasskeys();
        other.register(new PasskeyRegistration(key.credentialId, key.publicKey(), PasskeyAlgorithm.RS256, "other algorithm"));
        assertEquals("ADMIN_RECORD_ALTERED", AdminOpTest.verifier(other).fold(List.of(signed(key, key.keyAdd("my key"), null, 1).record())).failures.get(0).code());
    }
}
