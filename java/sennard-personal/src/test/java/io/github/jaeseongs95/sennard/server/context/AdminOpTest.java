// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.jvm.JvmPreset;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.server.limits.ResourceLimits;
import io.github.jaeseongs95.sennard.server.workflow.ConvergenceLimits;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The kind of a signed admin change is an enum written by its name, and each kind is one record ({@link AdminChange}) whose components and constraints are
 * its whole shape. The signature covers the canonical intent, so what the page signs and what the server reads must be the same names and the same keys;
 * a change of no kind, with a key more or fewer, or with a value of another type is refused before anything else about it is looked at.
 *
 * <pre>
 * o1  every kind     each {@link AdminOp} binds to its variant and writes the canonical intent a hand-built tree of the same JSON writes (the wire did not move)
 * o2  kind           the old dotted spelling, a lower-case name, an unknown name, a number and a missing kind are each refused as an invalid change
 * o3  keys           a known kind with a key more or fewer than its variant has is refused, whatever its values
 * o4  one set        the kinds and the variants are the same set
 * o5  JVM preset     one of the closed set by name; any other text (a JVM option written out included) is refused
 * o6  values         the limits and patterns of the values (lengths, identifiers, JSON types)
 * o7  convergence   the limits are two whole numbers of at least 1, no upper bound; 0, a negative number, a decimal number, text and a missing or extra key are refused
 * </pre>
 */
class AdminOpTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String ISSUED_AT = "2026-11-11T00:00:00.000Z";
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final AdminLogVerifier VERIFIER = verifier();

    /** A verifier over a registry of its own (nothing registered): what the tests of the shape of a change need. */
    static AdminLogVerifier verifier() {
        return verifier(new InMemoryPasskeys());
    }

    /** A verifier that checks signatures with the keys of this registry. */
    static AdminLogVerifier verifier(PasskeyRegistry passkeys) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return new AdminLogVerifier(MAPPER, validator, new AdminWebAuthn(MAPPER), passkeys);
    }

    private static JsonNode tree(String json) {
        return MAPPER.readTree(json);
    }

    private static SennardException refused(String json) {
        return assertThrows(SennardException.class, () -> VERIFIER.bindChange(tree(json)), json);
    }

    /** One change of each kind in the form the page writes it, and the chain it is appended to. */
    private record Sample(String json, String target) {
    }

    private static Map<AdminOp, Sample> samples() {
        String absolute = Path.of("").toAbsolutePath().toString();
        Map<AdminOp, Sample> samples = new EnumMap<>(AdminOp.class);
        samples.put(AdminOp.KEY_ADD, new Sample("{\"op\":\"KEY_ADD\",\"credentialId\":\"abc-1\",\"fingerprint\":\"" + FINGERPRINT + "\",\"alg\":-7,\"label\":\"test key\"}", "keys"));
        samples.put(AdminOp.KEY_REVOKE, new Sample("{\"op\":\"KEY_REVOKE\",\"credentialId\":\"abc-1\"}", "keys"));
        samples.put(AdminOp.GLOBAL_SET, new Sample("{\"op\":\"GLOBAL_SET\",\"body\":\"x\"}", "global"));
        samples.put(AdminOp.ROLE_ADD, new Sample("{\"op\":\"ROLE_ADD\",\"roleId\":\"role-1\",\"name\":\"Reviewer\"}", "roles"));
        samples.put(AdminOp.ROLE_RENAME, new Sample("{\"op\":\"ROLE_RENAME\",\"roleId\":\"role-1\",\"name\":\"Auditor\"}", "roles"));
        samples.put(AdminOp.ROLE_DELETE, new Sample("{\"op\":\"ROLE_DELETE\",\"roleId\":\"role-1\"}", "roles"));
        samples.put(AdminOp.ROLE_BODY_SET, new Sample("{\"op\":\"ROLE_BODY_SET\",\"roleId\":\"role-1\",\"body\":\"b\",\"expectedStateToken\":null}", "roleBody:role-1"));
        samples.put(AdminOp.BINDING_SET, new Sample("{\"op\":\"BINDING_SET\",\"host\":\"claude\",\"sessionId\":\"s 1\",\"roleId\":null}", "binding:claude:s%201"));
        samples.put(AdminOp.ACK, new Sample("{\"op\":\"ACK\",\"target\":\"global\"}", "global"));
        samples.put(AdminOp.JEV_SET, new Sample("{\"op\":\"JEV_SET\",\"enabled\":true,\"limitUsd\":2.5,\"model\":{\"providerId\":\"typesafe\",\"modelId\":\"jev-1.14.0\"},"
                + "\"fallbackModel\":{\"providerId\":\"typesafe\",\"modelId\":\"jev-1.13.0\"},\"presets\":{\"skill-classification\":true},\"transmissionAllowed\":false}", "jev"));
        samples.put(AdminOp.JEV_KEY_SET, new Sample("{\"op\":\"JEV_KEY_SET\",\"name\":\"JEV_KEY\",\"fingerprint\":\"" + FINGERPRINT + "\"}", "jevKeys"));
        samples.put(AdminOp.JEV_KEY_DELETE, new Sample("{\"op\":\"JEV_KEY_DELETE\",\"name\":\"JEV_KEY\"}", "jevKeys"));
        samples.put(AdminOp.EDIT_EXEMPT_SET, new Sample("{\"op\":\"EDIT_EXEMPT_SET\",\"paths\":" + MAPPER.writeValueAsString(List.of(absolute)) + "}", "editExempt"));
        samples.put(AdminOp.JVM_PRESET_SET, new Sample("{\"op\":\"JVM_PRESET_SET\",\"preset\":\"G1_2G\"}", "jvmPreset"));
        samples.put(AdminOp.CONVERGENCE_LIMITS_SET, new Sample("{\"op\":\"CONVERGENCE_LIMITS_SET\",\"limits\":{\"maxAttemptsPerEpoch\":5,\"maxEpochs\":4}}", "convergenceLimits"));
        samples.put(AdminOp.RESOURCE_LIMITS_SET, new Sample("{\"op\":\"RESOURCE_LIMITS_SET\",\"limits\":{\"csArtifactMaxBytes\":8388608,\"maxOpenWorkflowsPerSession\":5}}", "resourceLimits"));
        samples.put(AdminOp.ATTACHMENT_STORAGE_SET, new Sample(MAPPER.writeValueAsString(Map.of("op", "ATTACHMENT_STORAGE_SET", "settings", Map.of("directory", absolute, "maxFileBytes", 1073741824L, "stagingBudgetBytes", 4294967296L, "attachmentBudgetBytes", 10737418240L, "maxOpenPerOwner", 4, "diskMarginBytes", 67108864L))), "attachmentStorage"));
        samples.put(AdminOp.ROOT_APPROVE, new Sample("{\"op\":\"ROOT_APPROVE\",\"approvalId\":\"a-1\",\"scopeDigest\":\"" + FINGERPRINT + "\"}", "rootApproval:a-1"));
        samples.put(AdminOp.ROOT_RELEASE, new Sample("{\"op\":\"ROOT_RELEASE\",\"rootId\":\"root-1\",\"expectedRevision\":3,\"reason\":\"The owner session ended.\"}", "rootRelease:root-1"));
        // The link of the business database: the driver and the password file as paths, the URL without a password, the protection as a constant's name; no password anywhere.
        samples.put(AdminOp.DATABASE_LINK, new Sample("{\"op\":\"DATABASE_LINK\",\"driverPath\":" + MAPPER.writeValueAsString(absolute) + ",\"jdbcUrl\":\"jdbc:postgresql://127.0.0.1:5432/sennard\","
                + "\"accountName\":\"sennard_owner\",\"passwordRef\":" + MAPPER.writeValueAsString(absolute) + ",\"tls\":\"LOOPBACK_PLAINTEXT\"}", "database"));
        return samples;
    }

    /** The intent as the old code built it: a tree written key by key, its change the tree the page sent. */
    private static String handBuiltIntent(JsonNode change, String target, String prev) {
        ObjectNode intent = MAPPER.createObjectNode();
        intent.put("v", 1);
        intent.put("domain", "sennard.admin");
        intent.put("rpId", "localhost");
        intent.put("target", target);
        intent.put("prevTargetHash", prev);
        intent.set("change", change);
        intent.put("issuedAt", ISSUED_AT);
        return CanonicalJson.of(intent);
    }

    /** O1 */
    @ParameterizedTest
    @EnumSource(AdminOp.class)
    void aChangeOfEachKindBindsToItsVariantAndWritesTheCanonicalIntentTheWireHad(AdminOp op) {
        Sample sample = samples().get(op);
        JsonNode sent = tree(sample.json());
        AdminChange change = VERIFIER.bindChange(sent);
        assertEquals(op, AdminOp.of(change));
        assertEquals(sample.target(), AdminLog.changeTarget(change));
        for (String prev : new String[] {null, "sha256:" + "b".repeat(64)}) {
            String built = VERIFIER.buildIntent(change, prev, ISSUED_AT);
            assertEquals(handBuiltIntent(sent, sample.target(), prev), built, op.name());
            AdminLog.Intent read = VERIFIER.parseIntent(built);
            assertEquals(change, read.change(), op.name());
            assertEquals(prev, read.prevTargetHash(), op.name());
            assertEquals(sample.target(), read.target(), op.name());
            assertEquals(built, VERIFIER.buildIntent(read.change(), read.prevTargetHash(), read.issuedAt()), "reading and writing again changes nothing: " + op.name());
        }
    }

    /** O4 */
    @Test
    void theKindsAndTheSamplesAreTheSameSet() {
        assertEquals(EnumSet.allOf(AdminOp.class), samples().keySet());
    }

    /** O2 */
    @Test
    void aKindThatIsNoConstantNameIsRefused() {
        for (String bad : new String[] {"{\"op\":\"global.set\",\"body\":\"x\"}", "{\"op\":\"global_set\",\"body\":\"x\"}", "{\"op\":\"NOPE\",\"body\":\"x\"}", "{\"op\":3,\"body\":\"x\"}",
                "{\"body\":\"x\"}", "{\"op\":null,\"body\":\"x\"}", "[]", "\"GLOBAL_SET\"", "null"}) {
            assertEquals("ADMIN_CHANGE_INVALID", refused(bad).code().wireCode(), bad);
        }
        assertEquals("NOPE", refused("{\"op\":\"NOPE\",\"body\":\"x\"}").details().get("op"), "the refusal says which kind it was shown");
        assertEquals("ADMIN_CHANGE_INVALID", assertThrows(SennardException.class, () -> VERIFIER.bindChange(null)).code().wireCode());
    }

    /** O3 */
    @Test
    void aKnownKindWithAnotherKeySetIsRefused() {
        for (String bad : new String[] {"{\"op\":\"GLOBAL_SET\"}", "{\"op\":\"GLOBAL_SET\",\"body\":\"x\",\"extra\":1}", "{\"op\":\"ROLE_BODY_SET\",\"roleId\":\"r\",\"body\":\"b\"}",
                "{\"op\":\"BINDING_SET\",\"host\":\"h\",\"sessionId\":\"s\"}", "{\"op\":\"JEV_SET\",\"enabled\":true,\"limitUsd\":null,\"model\":null,\"fallbackModel\":null,\"presets\":{}}",
                "{\"op\":\"JEV_SET\",\"enabled\":true,\"limitUsd\":null,\"model\":{\"providerId\":\"p\",\"modelId\":\"m\",\"x\":1},\"fallbackModel\":null,\"presets\":{},\"transmissionAllowed\":true}",
                "{\"op\":\"JEV_SET\",\"enabled\":true,\"limitUsd\":null,\"model\":{\"providerId\":\"p\"},\"fallbackModel\":null,\"presets\":{},\"transmissionAllowed\":true}",
                "{\"op\":\"JEV_SET\",\"enabled\":true,\"limitUsd\":null,\"model\":null,\"presets\":{},\"transmissionAllowed\":true}",
                "{\"op\":\"JEV_SET\",\"enabled\":true,\"limitUsd\":null,\"model\":null,\"fallbackModel\":{\"providerId\":\"p\"},\"presets\":{},\"transmissionAllowed\":true}"}) {
            assertEquals("ADMIN_CHANGE_INVALID", refused(bad).code().wireCode(), bad);
        }
    }

    /** O5: the server's JVM preset is signed as one of the closed set, by name. */
    @ParameterizedTest
    @EnumSource(JvmPreset.class)
    void aJvmPresetChangeNamesOnePresetOfTheClosedSet(JvmPreset preset) {
        JsonNode sent = tree("{\"op\":\"JVM_PRESET_SET\",\"preset\":\"" + preset.name() + "\"}");
        AdminChange change = VERIFIER.bindChange(sent);
        assertEquals(new AdminChange.JvmPresetSet(preset), change);
        assertEquals(AdminOp.JVM_PRESET_SET, AdminOp.of(change));
        assertEquals("jvmPreset", AdminLog.changeTarget(change));
        assertTrue(AdminLog.isTarget("jvmPreset"));
    }

    /** O5: any other text (a JVM option written out included) is refused, so no signed change can put one into a launcher. */
    @Test
    void aJvmPresetChangeThatIsNotOnePresetOfTheSetIsRefused() {
        for (String bad : new String[] {"{\"op\":\"JVM_PRESET_SET\",\"preset\":\"-Xmx64g\"}", "{\"op\":\"JVM_PRESET_SET\",\"preset\":\"g1_2g\"}", "{\"op\":\"JVM_PRESET_SET\",\"preset\":\"G1_2G -Xmx64g\"}",
                "{\"op\":\"JVM_PRESET_SET\",\"preset\":\"\"}", "{\"op\":\"JVM_PRESET_SET\",\"preset\":3}", "{\"op\":\"JVM_PRESET_SET\",\"preset\":0}", "{\"op\":\"JVM_PRESET_SET\",\"preset\":null}",
                "{\"op\":\"JVM_PRESET_SET\"}", "{\"op\":\"JVM_PRESET_SET\",\"preset\":\"G1_2G\",\"options\":\"-Xmx64g\"}"}) {
            assertEquals("ADMIN_CHANGE_INVALID", refused(bad).code().wireCode(), bad);
        }
    }

    /** O7: the convergence limits are two whole numbers of at least 1 with no upper bound. */
    @ParameterizedTest
    @CsvSource({"1,1", "3,2", "5,4", "1000,2147483647"})
    void aConvergenceLimitsChangeTakesAnyWholeNumbersFromOne(int attempts, int epochs) {
        AdminChange change = VERIFIER.bindChange(tree("{\"op\":\"CONVERGENCE_LIMITS_SET\",\"limits\":{\"maxAttemptsPerEpoch\":" + attempts + ",\"maxEpochs\":" + epochs + "}}"));
        assertEquals(new AdminChange.ConvergenceLimitsSet(new ConvergenceLimits(attempts, epochs)), change);
        assertEquals(AdminOp.CONVERGENCE_LIMITS_SET, AdminOp.of(change));
        assertEquals("convergenceLimits", AdminLog.changeTarget(change));
        assertTrue(AdminLog.isTarget("convergenceLimits"));
    }

    /** O7: zero, a negative number, a decimal number, text, null, a missing key and a key too many are refused before any signature is asked for. */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"op\":\"CONVERGENCE_LIMITS_SET\",\"limits\":{\"maxAttemptsPerEpoch\":0,\"maxEpochs\":2}}",
            "{\"op\":\"CONVERGENCE_LIMITS_SET\",\"limits\":{\"maxAttemptsPerEpoch\":3,\"maxEpochs\":0}}",
            "{\"op\":\"CONVERGENCE_LIMITS_SET\",\"limits\":{\"maxAttemptsPerEpoch\":-1,\"maxEpochs\":2}}",
            "{\"op\":\"CONVERGENCE_LIMITS_SET\",\"limits\":{\"maxAttemptsPerEpoch\":3,\"maxEpochs\":-2}}",
            "{\"op\":\"CONVERGENCE_LIMITS_SET\",\"limits\":{\"maxAttemptsPerEpoch\":2.5,\"maxEpochs\":2}}",
            "{\"op\":\"CONVERGENCE_LIMITS_SET\",\"limits\":{\"maxAttemptsPerEpoch\":3,\"maxEpochs\":2.0}}",
            "{\"op\":\"CONVERGENCE_LIMITS_SET\",\"limits\":{\"maxAttemptsPerEpoch\":\"3\",\"maxEpochs\":2}}",
            "{\"op\":\"CONVERGENCE_LIMITS_SET\",\"limits\":{\"maxAttemptsPerEpoch\":3,\"maxEpochs\":\"two\"}}",
            "{\"op\":\"CONVERGENCE_LIMITS_SET\",\"limits\":{\"maxAttemptsPerEpoch\":3,\"maxEpochs\":true}}",
            "{\"op\":\"CONVERGENCE_LIMITS_SET\",\"limits\":{\"maxAttemptsPerEpoch\":null,\"maxEpochs\":2}}",
            "{\"op\":\"CONVERGENCE_LIMITS_SET\",\"limits\":{\"maxAttemptsPerEpoch\":3}}",
            "{\"op\":\"CONVERGENCE_LIMITS_SET\",\"limits\":{\"maxAttemptsPerEpoch\":3,\"maxEpochs\":2,\"leaseTtlSeconds\":9}}",
            "{\"op\":\"CONVERGENCE_LIMITS_SET\",\"limits\":null}",
            "{\"op\":\"CONVERGENCE_LIMITS_SET\"}",
            "{\"op\":\"CONVERGENCE_LIMITS_SET\",\"maxAttemptsPerEpoch\":3,\"maxEpochs\":2}",
            "{\"op\":\"CONVERGENCE_LIMITS_SET\",\"limits\":{\"maxAttemptsPerEpoch\":99999999999,\"maxEpochs\":2}}"})
    void aConvergenceLimitsChangeThatIsNotTwoWholeNumbersOfAtLeastOneIsRefused(String bad) {
        assertEquals("ADMIN_CHANGE_INVALID", refused(bad).code().wireCode(), bad);
    }

    @ParameterizedTest
    @CsvSource({"1048576,1", "8388608,5", "67108864,2147483647"})
    void resourceLimitsTakeInRangeIntegersIncludingBothBoundaries(int bytes, int workflows) {
        AdminChange change = VERIFIER.bindChange(tree("{\"op\":\"RESOURCE_LIMITS_SET\",\"limits\":{\"csArtifactMaxBytes\":" + bytes
                + ",\"maxOpenWorkflowsPerSession\":" + workflows + "}}"));
        assertEquals(new AdminChange.ResourceLimitsSet(new ResourceLimits(bytes, workflows)), change);
        assertEquals(AdminOp.RESOURCE_LIMITS_SET, AdminOp.of(change));
        assertEquals("resourceLimits", AdminLog.changeTarget(change));
        assertTrue(AdminLog.isTarget("resourceLimits"));
    }

    static Stream<String> valuesThatAreRefused() {
        String longToken = "t".repeat(201);
        return Stream.of(
                // key registration: identifier, key text length, algorithm as a whole number, label length
                "{\"op\":\"KEY_ADD\",\"credentialId\":\"a b\",\"fingerprint\":\"" + FINGERPRINT + "\",\"alg\":-7,\"label\":\"k\"}",
                "{\"op\":\"KEY_ADD\",\"credentialId\":\"" + "c".repeat(201) + "\",\"fingerprint\":\"" + FINGERPRINT + "\",\"alg\":-7,\"label\":\"k\"}",
                "{\"op\":\"KEY_ADD\",\"credentialId\":\"abc\",\"fingerprint\":\"\",\"alg\":-7,\"label\":\"k\"}",
                "{\"op\":\"KEY_ADD\",\"credentialId\":\"abc\",\"fingerprint\":\"" + FINGERPRINT + "a\",\"alg\":-7,\"label\":\"k\"}",
                "{\"op\":\"KEY_ADD\",\"credentialId\":\"abc\",\"fingerprint\":\"sha256:ABC\",\"alg\":-7,\"label\":\"k\"}",
                // the key itself is no part of the record: a record that still carries one is refused, so no public key is written to the business database
                "{\"op\":\"KEY_ADD\",\"credentialId\":\"abc\",\"fingerprint\":\"" + FINGERPRINT + "\",\"publicKey\":\"AAAA\",\"alg\":-7,\"label\":\"k\"}",
                "{\"op\":\"KEY_ADD\",\"credentialId\":\"abc\",\"publicKey\":\"AAAA\",\"alg\":-7,\"label\":\"k\"}",
                "{\"op\":\"KEY_ADD\",\"credentialId\":\"abc\",\"fingerprint\":\"" + FINGERPRINT + "\",\"alg\":\"-7\",\"label\":\"k\"}",
                "{\"op\":\"KEY_ADD\",\"credentialId\":\"abc\",\"fingerprint\":\"" + FINGERPRINT + "\",\"alg\":-7.5,\"label\":\"k\"}",
                "{\"op\":\"KEY_ADD\",\"credentialId\":\"abc\",\"fingerprint\":\"" + FINGERPRINT + "\",\"alg\":null,\"label\":\"k\"}",
                "{\"op\":\"KEY_ADD\",\"credentialId\":\"abc\",\"fingerprint\":\"" + FINGERPRINT + "\",\"alg\":-7,\"label\":\"" + "l".repeat(61) + "\"}",
                "{\"op\":\"KEY_ADD\",\"credentialId\":\"abc\",\"fingerprint\":\"" + FINGERPRINT + "\",\"alg\":-7,\"label\":null}",
                // text where a number or a flag goes, and a number where text goes
                "{\"op\":\"GLOBAL_SET\",\"body\":5}", "{\"op\":\"GLOBAL_SET\",\"body\":null}", "{\"op\":\"GLOBAL_SET\",\"body\":[\"x\"]}",
                // role names are exactly their normalized form
                "{\"op\":\"ROLE_ADD\",\"roleId\":\"role-1\",\"name\":\" padded \"}", "{\"op\":\"ROLE_ADD\",\"roleId\":\"role-1\",\"name\":\"\"}",
                "{\"op\":\"ROLE_ADD\",\"roleId\":\"role-1\",\"name\":\"two\\nlines\"}", "{\"op\":\"ROLE_ADD\",\"roleId\":\"role-1\",\"name\":\"" + "n".repeat(101) + "\"}",
                "{\"op\":\"ROLE_RENAME\",\"roleId\":\"role-1\",\"name\":\"e\\u0301\"}",
                "{\"op\":\"ROLE_DELETE\",\"roleId\":\"no way\"}",
                // role body: the expected token is a text of 1 to 200 characters or null
                "{\"op\":\"ROLE_BODY_SET\",\"roleId\":\"role-1\",\"body\":\"b\",\"expectedStateToken\":\"\"}",
                "{\"op\":\"ROLE_BODY_SET\",\"roleId\":\"role-1\",\"body\":\"b\",\"expectedStateToken\":\"" + longToken + "\"}",
                "{\"op\":\"ROLE_BODY_SET\",\"roleId\":\"role-1\",\"body\":null,\"expectedStateToken\":null}",
                // binding
                "{\"op\":\"BINDING_SET\",\"host\":\"\",\"sessionId\":\"s\",\"roleId\":null}", "{\"op\":\"BINDING_SET\",\"host\":\"" + "h".repeat(201) + "\",\"sessionId\":\"s\",\"roleId\":null}",
                "{\"op\":\"BINDING_SET\",\"host\":\"h\",\"sessionId\":\"" + "s".repeat(501) + "\",\"roleId\":null}", "{\"op\":\"BINDING_SET\",\"host\":\"h\",\"sessionId\":\"s\",\"roleId\":\"no way\"}",
                // ack: the name of a chain of the log, in the form the log writes it
                "{\"op\":\"ACK\",\"target\":\"nope\"}", "{\"op\":\"ACK\",\"target\":\"binding:a b:c\"}", "{\"op\":\"ACK\",\"target\":null}",
                // JEV settings
                "{\"op\":\"JEV_SET\",\"enabled\":\"true\",\"limitUsd\":null,\"model\":null,\"fallbackModel\":null,\"presets\":{},\"transmissionAllowed\":true}",
                "{\"op\":\"JEV_SET\",\"enabled\":true,\"limitUsd\":-1,\"model\":null,\"fallbackModel\":null,\"presets\":{},\"transmissionAllowed\":true}",
                "{\"op\":\"JEV_SET\",\"enabled\":true,\"limitUsd\":\"5\",\"model\":null,\"fallbackModel\":null,\"presets\":{},\"transmissionAllowed\":true}",
                "{\"op\":\"JEV_SET\",\"enabled\":true,\"limitUsd\":null,\"model\":null,\"fallbackModel\":null,\"presets\":{},\"transmissionAllowed\":null}",
                "{\"op\":\"JEV_SET\",\"enabled\":true,\"limitUsd\":null,\"model\":null,\"fallbackModel\":null,\"presets\":{\"Upper\":true},\"transmissionAllowed\":true}",
                "{\"op\":\"JEV_SET\",\"enabled\":true,\"limitUsd\":null,\"model\":null,\"fallbackModel\":null,\"presets\":{\"a\":\"yes\"},\"transmissionAllowed\":true}",
                "{\"op\":\"JEV_SET\",\"enabled\":true,\"limitUsd\":null,\"model\":null,\"fallbackModel\":null,\"presets\":{\"a\":null},\"transmissionAllowed\":true}",
                "{\"op\":\"JEV_SET\",\"enabled\":true,\"limitUsd\":null,\"model\":null,\"fallbackModel\":null,\"presets\":null,\"transmissionAllowed\":true}",
                "{\"op\":\"JEV_SET\",\"enabled\":true,\"limitUsd\":null,\"model\":{\"providerId\":\"Bad Id\",\"modelId\":\"m\"},\"fallbackModel\":null,\"presets\":{},\"transmissionAllowed\":true}",
                "{\"op\":\"JEV_SET\",\"enabled\":true,\"limitUsd\":null,\"model\":null,\"fallbackModel\":{\"providerId\":\"Bad Id\",\"modelId\":\"m\"},\"presets\":{},\"transmissionAllowed\":true}",
                "{\"op\":\"JEV_KEY_SET\",\"name\":\"1bad\",\"fingerprint\":\"" + FINGERPRINT + "\"}", "{\"op\":\"JEV_KEY_SET\",\"name\":\"ok\",\"fingerprint\":\"sha256:ABC\"}",
                "{\"op\":\"JEV_KEY_DELETE\",\"name\":\"has space\"}",
                // edit-exempt paths: relative, white space at an end, a control character, empty, twice, too long a list or path
                "{\"op\":\"EDIT_EXEMPT_SET\",\"paths\":[\"relative/path\"]}", "{\"op\":\"EDIT_EXEMPT_SET\",\"paths\":[\"\"]}", "{\"op\":\"EDIT_EXEMPT_SET\",\"paths\":[null]}",
                "{\"op\":\"EDIT_EXEMPT_SET\",\"paths\":null}", "{\"op\":\"EDIT_EXEMPT_SET\",\"paths\":\"" + Path.of("").toAbsolutePath().toString().replace("\\", "\\\\") + "\"}",
                // approval
                "{\"op\":\"ROOT_APPROVE\",\"approvalId\":\"a-1\",\"scopeDigest\":\"sha256:short\"}", "{\"op\":\"ROOT_APPROVE\",\"approvalId\":\"\",\"scopeDigest\":\"" + FINGERPRINT + "\"}",
                // a release by the user: a root ID of the identifier's alphabet, a revision that is a whole number of at least zero, a reason that is not blank and fits the root's contract
                "{\"op\":\"ROOT_RELEASE\",\"rootId\":\"\",\"expectedRevision\":1,\"reason\":\"r\"}", "{\"op\":\"ROOT_RELEASE\",\"rootId\":\"root 1\",\"expectedRevision\":1,\"reason\":\"r\"}",
                "{\"op\":\"ROOT_RELEASE\",\"rootId\":null,\"expectedRevision\":1,\"reason\":\"r\"}", "{\"op\":\"ROOT_RELEASE\",\"rootId\":\"root-1\",\"expectedRevision\":-1,\"reason\":\"r\"}",
                "{\"op\":\"ROOT_RELEASE\",\"rootId\":\"root-1\",\"expectedRevision\":\"1\",\"reason\":\"r\"}", "{\"op\":\"ROOT_RELEASE\",\"rootId\":\"root-1\",\"expectedRevision\":null,\"reason\":\"r\"}",
                "{\"op\":\"ROOT_RELEASE\",\"rootId\":\"root-1\",\"expectedRevision\":1.5,\"reason\":\"r\"}", "{\"op\":\"ROOT_RELEASE\",\"rootId\":\"root-1\",\"expectedRevision\":1,\"reason\":\"\"}",
                "{\"op\":\"ROOT_RELEASE\",\"rootId\":\"root-1\",\"expectedRevision\":1,\"reason\":\"   \"}", "{\"op\":\"ROOT_RELEASE\",\"rootId\":\"root-1\",\"expectedRevision\":1,\"reason\":null}",
                "{\"op\":\"ROOT_RELEASE\",\"rootId\":\"root-1\",\"expectedRevision\":1,\"reason\":\"" + "r".repeat(2001) + "\"}", "{\"op\":\"ROOT_RELEASE\",\"rootId\":\"root-1\",\"expectedRevision\":1,\"reason\":3}",
                // the link of the database: a URL that is no JDBC URL, a protection that is no constant, a missing or blank part, and a password written beside the rest (no such key)
                "{\"op\":\"DATABASE_LINK\",\"driverPath\":\"/d\",\"jdbcUrl\":\"postgresql://h/db\",\"accountName\":\"a\",\"passwordRef\":\"/p\",\"tls\":\"LOOPBACK_PLAINTEXT\"}",
                "{\"op\":\"DATABASE_LINK\",\"driverPath\":\"/d\",\"jdbcUrl\":\"jdbc:postgresql://h/db\",\"accountName\":\"a\",\"passwordRef\":\"/p\",\"tls\":\"SOMETIMES\"}",
                "{\"op\":\"DATABASE_LINK\",\"driverPath\":\"/d\",\"jdbcUrl\":\"jdbc:postgresql://h/db\",\"accountName\":\"a\",\"passwordRef\":\"/p\",\"tls\":null}",
                "{\"op\":\"DATABASE_LINK\",\"driverPath\":\"\",\"jdbcUrl\":\"jdbc:postgresql://h/db\",\"accountName\":\"a\",\"passwordRef\":\"/p\",\"tls\":\"VERIFY_FULL\"}",
                "{\"op\":\"DATABASE_LINK\",\"driverPath\":\"/d\",\"jdbcUrl\":\"jdbc:postgresql://h/db\",\"accountName\":\"  \",\"passwordRef\":\"/p\",\"tls\":\"VERIFY_FULL\"}",
                "{\"op\":\"DATABASE_LINK\",\"driverPath\":\"/d\",\"jdbcUrl\":\"jdbc:postgresql://h/db\",\"accountName\":\"a\",\"passwordRef\":null,\"tls\":\"VERIFY_FULL\"}",
                "{\"op\":\"DATABASE_LINK\",\"driverPath\":\"/d\",\"jdbcUrl\":\"jdbc:postgresql://h/db\",\"accountName\":\"a\",\"passwordRef\":\"/p\",\"password\":\"x\",\"tls\":\"VERIFY_FULL\"}",
                "{\"op\":\"DATABASE_LINK\",\"driverPath\":\"/d\",\"jdbcUrl\":\"jdbc:postgresql://h/db\",\"accountName\":\"a\",\"passwordRef\":\"/p\"}");
    }

    /** O6 */
    @ParameterizedTest
    @MethodSource("valuesThatAreRefused")
    void aValueOutsideTheLimitsOfItsKindIsRefused(String json) {
        assertEquals("ADMIN_CHANGE_INVALID", refused(json).code().wireCode(), json);
    }

    /** O6: the edge of each limit passes; a number a whole number, a null where the kind allows one. */
    @Test
    void aValueOnTheEdgeOfItsLimitsPasses() {
        String absolute = MAPPER.writeValueAsString(Path.of("").toAbsolutePath().toString());
        for (String good : new String[] {
                "{\"op\":\"KEY_ADD\",\"credentialId\":\"" + "c".repeat(200) + "\",\"fingerprint\":\"" + FINGERPRINT + "\",\"alg\":-257,\"label\":\"" + "l".repeat(60) + "\"}",
                "{\"op\":\"KEY_ADD\",\"credentialId\":\"abc\",\"fingerprint\":\"" + FINGERPRINT + "\",\"alg\":-7,\"label\":\"\"}",
                "{\"op\":\"GLOBAL_SET\",\"body\":\"\"}",
                "{\"op\":\"ROLE_ADD\",\"roleId\":\"role-1\",\"name\":\"" + "n".repeat(100) + "\"}",
                "{\"op\":\"ROLE_BODY_SET\",\"roleId\":\"role-1\",\"body\":\"\",\"expectedStateToken\":\"" + "t".repeat(200) + "\"}",
                "{\"op\":\"BINDING_SET\",\"host\":\"" + "h".repeat(200) + "\",\"sessionId\":\"" + "s".repeat(500) + "\",\"roleId\":\"role-1\"}",
                "{\"op\":\"ACK\",\"target\":\"binding:claude:s%201\"}", "{\"op\":\"ACK\",\"target\":\"roleBody:role-1\"}", "{\"op\":\"ACK\",\"target\":\"rootApproval:a-1\"}",
                "{\"op\":\"ACK\",\"target\":\"rootRelease:root-1\"}", "{\"op\":\"ROOT_RELEASE\",\"rootId\":\"" + "r".repeat(200) + "\",\"expectedRevision\":0,\"reason\":\"" + "r".repeat(2000) + "\"}",
                "{\"op\":\"JEV_SET\",\"enabled\":false,\"limitUsd\":0,\"model\":null,\"fallbackModel\":null,\"presets\":{},\"transmissionAllowed\":false}",
                "{\"op\":\"JEV_SET\",\"enabled\":true,\"limitUsd\":null,\"model\":null,\"fallbackModel\":null,\"presets\":{\"a\":true,\"b\":false},\"transmissionAllowed\":true}",
                "{\"op\":\"EDIT_EXEMPT_SET\",\"paths\":[]}", "{\"op\":\"EDIT_EXEMPT_SET\",\"paths\":[" + absolute + "]}"}) {
            assertNotNull(VERIFIER.bindChange(tree(good)), good);
        }
        assertEquals(3, ((AdminChange.JevSet) VERIFIER.bindChange(tree("{\"op\":\"JEV_SET\",\"enabled\":true,\"limitUsd\":3,\"model\":null,\"fallbackModel\":null,\"presets\":{},\"transmissionAllowed\":true}"))).limitUsd().intValue(),
                "a whole number is a limit");
    }

    /** O6: a list of 51 exempt paths, and a list that names a path twice, are refused. */
    @Test
    void theExemptListHasAtMost50DifferentPaths() {
        Path root = Path.of("").toAbsolutePath();
        List<String> fifty = java.util.stream.IntStream.range(0, 50).mapToObj(i -> root.resolve("p" + i).toString()).toList();
        assertNotNull(VERIFIER.bindChange(tree("{\"op\":\"EDIT_EXEMPT_SET\",\"paths\":" + MAPPER.writeValueAsString(fifty) + "}")));
        List<String> fiftyOne = java.util.stream.IntStream.range(0, 51).mapToObj(i -> root.resolve("p" + i).toString()).toList();
        assertEquals("ADMIN_CHANGE_INVALID", refused("{\"op\":\"EDIT_EXEMPT_SET\",\"paths\":" + MAPPER.writeValueAsString(fiftyOne) + "}").code().wireCode());
        String twice = root.resolve("same").toString();
        assertEquals("ADMIN_CHANGE_INVALID", refused("{\"op\":\"EDIT_EXEMPT_SET\",\"paths\":" + MAPPER.writeValueAsString(List.of(twice, twice)) + "}").code().wireCode());
        assertEquals("ADMIN_CHANGE_INVALID", refused("{\"op\":\"EDIT_EXEMPT_SET\",\"paths\":" + MAPPER.writeValueAsString(List.of(twice + " ")) + "}").code().wireCode(), "white space at the end");
        assertEquals("ADMIN_CHANGE_INVALID", refused("{\"op\":\"EDIT_EXEMPT_SET\",\"paths\":" + MAPPER.writeValueAsString(List.of(twice + "\u0007")) + "}").code().wireCode(), "a control character");
        assertEquals("ADMIN_CHANGE_INVALID", refused("{\"op\":\"EDIT_EXEMPT_SET\",\"paths\":" + MAPPER.writeValueAsString(List.of(root.resolve("x".repeat(1100)).toString())) + "}").code().wireCode(), "over 1024 characters");
    }
}
