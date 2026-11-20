// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The convergence limits on the admin page (how many full attempts one epoch allows and how many epochs a root may open), with the real signed log and the workflow tools
 * of the same server. The passkey is a software authenticator. A new root takes the signed values into its frame; a root that is already open keeps its own.
 *
 * <pre>
 * c1  unset      nothing signed: the page state says 3 and 2 (the defaults, not signed), and a new root's frame and status say 3 and 2
 * c2  refused    0, a negative number, a decimal number, text, a missing and an extra key are refused before a signature is asked for; the state does not change
 * c3  signed     a passkey-signed CONVERGENCE_LIMITS_SET shows in the state, in the signed settings and in the page's convergence view
 * c4  new root   a root opened after the change has the new values in its frame and its status; a root opened before keeps 3 and 2
 * c5  enforced   with 1 attempt and 1 epoch the root is gated after its first attempt (review required), while the root opened before the change still takes a second attempt
 * c6  no limit   a value above the old fixed 3 and 2 is taken, shown in the status, and counted down as attempts start
 * c7  not sent   the limits written into the frame of open_convergence_root are replaced by the setting in force
 * c8  back       signing 3 and 2 again gives the defaults' values to the next root (the signed setting stays recorded as signed)
 * </pre>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("business-db")
class ConvergenceLimitsServerTest {
    private static ToolServerSupport sennard;
    private static ToolServerSupport.Passkey owner;
    private static Path workspaces;
    /** A root opened while nothing was signed, kept for the cases after the change. */
    private static Started early;

    @BeforeAll
    static void start() throws Exception {
        Path repository = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(repository.resolve("contracts")) || !Files.isDirectory(repository.resolve("skills"))) {
            repository = repository.getParent();
        }
        workspaces = Path.of("build", "convergence-limits-it", UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(workspaces);
        sennard = ToolServerSupport.startWith("--sennard.content-root=" + repository, "--sennard.skills-dir=" + repository.resolve("skills"));
        owner = sennard.owner();
    }

    @AfterAll
    static void stop() {
        sennard.close();
    }

    // ------------------------------------------------------------------------------------------------------------ harness

    private static Map<String, Object> arguments(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    private static JsonNode ok(JsonNode result) {
        assertTrue(result.path("ok").asBoolean(false), result.toString());
        return result.get("data");
    }

    private static ObjectNode change(int attempts, int epochs) {
        ObjectNode limits = ToolServerSupport.MAPPER.createObjectNode().put("maxAttemptsPerEpoch", attempts).put("maxEpochs", epochs);
        ObjectNode change = ToolServerSupport.MAPPER.createObjectNode().put("op", "CONVERGENCE_LIMITS_SET");
        change.set("limits", limits);
        return change;
    }

    private static JsonNode convergence() throws Exception {
        return sennard.state().path("settings").path("convergence");
    }

    private static void assertLimits(JsonNode limits, int attempts, int epochs) {
        assertEquals(attempts, limits.path("maxAttemptsPerEpoch").asInt(-1), limits.toString());
        assertEquals(epochs, limits.path("maxEpochs").asInt(-1), limits.toString());
    }

    /** The arguments of a minimal start in a workspace of its own, so that two roots never share a write surface. */
    private static Map<String, Object> startArguments() throws Exception {
        Path workspace = Files.createDirectories(workspaces.resolve(UUID.randomUUID().toString()));
        return arguments("goal", "Change alpha in " + workspace.getFileName(), "stages", List.of("minimal-implementation"), "skills", List.of(arguments("skillId", "minimal-implementation", "reason", "test")),
                "acceptanceCriteria", List.of("The change works."), "workspace", workspace.toString(), "writeTargets", List.of("alpha"));
    }

    /** A root with the run its latest attempt started, and the arguments the session started it with. */
    private record Started(String session, String rootId, String runId, long revision, Map<String, Object> arguments) {
        static Started of(JsonNode started, String session, Map<String, Object> arguments) {
            return new Started(session, started.get("rootId").asString(), started.get("runId").asString(), started.get("revision").asLong(), arguments);
        }
    }

    private static Started startWorkflow() throws Exception {
        Map<String, Object> arguments = startArguments();
        String session = sennard.session();
        return Started.of(ok(sennard.callAsNewHostCall("start_workflow", arguments, session)), session, arguments);
    }

    private static JsonNode status(String rootId) throws Exception {
        return ok(sennard.call("get_convergence_status", arguments("rootId", rootId), sennard.session()));
    }

    private static void assertRootLimits(JsonNode status, int attempts, int epochs) {
        assertEquals(attempts, status.path("maxAttemptsPerEpoch").asInt(-1), status.toString());
        assertEquals(epochs, status.path("maxEpochs").asInt(-1), status.toString());
        assertLimits(status.path("root").path("frame").path("operationalSettings"), attempts, epochs);
    }

    private static void abort(Started started) {
        ok(sennard.call("abort_workflow", arguments("runId", started.runId(), "expectedRevision", started.revision()), started.session()));
    }

    /** The prior failure's evidence is an attachment of the session that continues the root: new evidence is new bytes the server holds, so each continuation uploads text of its own. */
    private static Map<String, Object> continued(Started started) throws Exception {
        ToolServerSupport.Uploaded evidence = sennard.uploadEvidence(started.session());
        Map<String, Object> next = new LinkedHashMap<>(started.arguments());
        next.put("rootId", started.rootId());
        next.put("priorFailure", arguments("hypothesis", "h", "changeSummary", "c", "discriminator", "d", "evidenceRefs", List.of(evidence.attachmentId())));
        next.put("skills", List.of(arguments("skillId", "blocker-diagnostician", "reason", "the attempt failed")));
        return next;
    }

    // ------------------------------------------------------------------------------------------------------------ cases

    @Test
    @Order(1)
    void withNothingSignedThePageAndANewRootSayThreeAndTwo() throws Exception {
        JsonNode view = convergence();
        assertLimits(view.path("limits"), 3, 2);
        assertLimits(view.path("defaults"), 3, 2);
        assertFalse(view.path("signed").asBoolean(true), view.toString());
        JsonNode signed = sennard.state().path("signedSettings").path("convergenceLimits");
        assertTrue(signed.isNull() || signed.isMissingNode(), "nothing is signed: " + signed);

        Started started = startWorkflow();
        JsonNode status = status(started.rootId());
        assertRootLimits(status, 3, 2);
        assertEquals(1, status.path("attemptsUsedInEpoch").asInt(), status.toString());
        assertEquals(2, status.path("attemptsRemainingInEpoch").asInt(), status.toString());
        early = started;
    }

    @Test
    @Order(2)
    void valuesThatAreNotWholeNumbersOfAtLeastOneAreRefusedBeforeASignatureIsAsked() throws Exception {
        List<String> bad = List.of("{\"maxAttemptsPerEpoch\":0,\"maxEpochs\":2}", "{\"maxAttemptsPerEpoch\":3,\"maxEpochs\":0}", "{\"maxAttemptsPerEpoch\":-1,\"maxEpochs\":2}",
                "{\"maxAttemptsPerEpoch\":3,\"maxEpochs\":-2}", "{\"maxAttemptsPerEpoch\":2.5,\"maxEpochs\":2}", "{\"maxAttemptsPerEpoch\":3,\"maxEpochs\":\"2\"}",
                "{\"maxAttemptsPerEpoch\":\"three\",\"maxEpochs\":2}", "{\"maxAttemptsPerEpoch\":null,\"maxEpochs\":2}", "{\"maxAttemptsPerEpoch\":3}",
                "{\"maxAttemptsPerEpoch\":3,\"maxEpochs\":2,\"leaseTtlSeconds\":5}", "null");
        for (String limits : bad) {
            ObjectNode change = ToolServerSupport.MAPPER.createObjectNode().put("op", "CONVERGENCE_LIMITS_SET");
            change.set("limits", ToolServerSupport.MAPPER.readTree(limits));
            ObjectNode body = ToolServerSupport.MAPPER.createObjectNode();
            body.set("change", change);
            body.put("bootstrap", false);
            RawHttp.Response refused = sennard.post("/api/intent", body);
            assertEquals(400, refused.status(), limits + " " + refused.text());
            assertEquals("ADMIN_CHANGE_INVALID", SessionServerSupport.json(refused).path("code").asString(), limits);
        }
        JsonNode view = convergence();
        assertLimits(view.path("limits"), 3, 2);
        assertFalse(view.path("signed").asBoolean(true), "nothing was signed");
    }

    @Test
    @Order(3)
    void aSignedChangeShowsInTheState() throws Exception {
        sennard.sign(owner, change(1, 1));
        JsonNode view = convergence();
        assertLimits(view.path("limits"), 1, 1);
        assertLimits(view.path("defaults"), 3, 2);
        assertTrue(view.path("signed").asBoolean(false), view.toString());
        assertLimits(sennard.state().path("signedSettings").path("convergenceLimits").path("limits"), 1, 1);
    }

    @Test
    @Order(4)
    void aRootOpenedAfterTheChangeHasTheNewValuesAndARootOpenedBeforeKeepsItsOwn() throws Exception {
        assertRootLimits(status(early.rootId()), 3, 2);
        Started late = startWorkflow();
        JsonNode status = status(late.rootId());
        assertRootLimits(status, 1, 1);
        assertEquals(1, status.path("attemptsUsedInEpoch").asInt(), status.toString());
        assertEquals(0, status.path("attemptsRemainingInEpoch").asInt(), status.toString());
        // The change did not rewrite the earlier root.
        assertRootLimits(status(early.rootId()), 3, 2);
    }

    @Test
    @Order(5)
    void theServerStopsARootAtItsOwnLimitNotAtTheCurrentSettingOrAFixedThree() throws Exception {
        // 1 attempt in 1 epoch: after the first attempt ends the root is gated and the next full attempt is refused.
        Started tight = startWorkflow();
        abort(tight);
        JsonNode gated = status(tight.rootId());
        assertEquals("NEEDS_REVIEW", gated.path("root").path("state").asString(), gated.toString());
        assertEquals("ATTEMPT_BUDGET_EXHAUSTED", gated.path("gateError").path("code").asString(), gated.toString());
        assertEquals(0, gated.path("attemptsRemainingInEpoch").asInt(), gated.toString());
        JsonNode refused = sennard.callAsNewHostCall("start_workflow", continued(tight), tight.session());
        assertFalse(refused.path("ok").asBoolean(true), refused.toString());
        assertEquals("ROOT_NOT_OPEN", refused.path("error").path("details").path("reason").asString(), refused.toString());

        // The root opened while the limit was 3 has not been gated by the setting of 1: its first attempt ended and it takes a second.
        abort(early);
        assertEquals("OPEN", status(early.rootId()).path("root").path("state").asString());
        JsonNode resumed = ok(sennard.callAsNewHostCall("start_workflow", continued(early), early.session()));
        assertEquals(early.rootId(), resumed.get("rootId").asString());
        JsonNode after = status(early.rootId());
        assertEquals(2, after.path("attemptsUsedInEpoch").asInt(), after.toString());
        assertEquals(1, after.path("attemptsRemainingInEpoch").asInt(), after.toString());
    }

    @Test
    @Order(6)
    void aLimitAboveTheOldFixedValuesIsTakenAndCountedDown() throws Exception {
        sennard.sign(owner, change(5, 4));
        assertLimits(convergence().path("limits"), 5, 4);
        Started wide = startWorkflow();
        JsonNode status = status(wide.rootId());
        assertRootLimits(status, 5, 4);
        assertEquals(1, status.path("attemptsUsedInEpoch").asInt(), status.toString());
        assertEquals(4, status.path("attemptsRemainingInEpoch").asInt(), status.toString());
        // Three attempts later the root is still open, where the old fixed rule would have gated it.
        Started current = wide;
        for (int attempt = 2; attempt <= 4; attempt++) {
            abort(current);
            assertEquals("OPEN", status(wide.rootId()).path("root").path("state").asString(), "attempt " + (attempt - 1) + " ended");
            current = Started.of(ok(sennard.callAsNewHostCall("start_workflow", continued(current), current.session())), current.session(), current.arguments());
        }
        JsonNode fourth = status(wide.rootId());
        assertEquals(4, fourth.path("attemptsUsedInEpoch").asInt(), fourth.toString());
        assertEquals(1, fourth.path("attemptsRemainingInEpoch").asInt(), fourth.toString());
    }

    @Test
    @Order(7)
    void theLimitsWrittenIntoTheFrameOfOpenConvergenceRootAreReplacedByTheSetting() throws Exception {
        String workspace = Files.createDirectories(workspaces.resolve(UUID.randomUUID().toString())).toString();
        Map<String, Object> task = arguments("schemaVersion", "1.0.0", "taskId", "limits-task-" + UUID.randomUUID(), "objective", "Change a file", "scope", Map.of("included", List.of("f"), "excluded", List.of()),
                "acceptanceCriteria", List.of("a"), "riskLevel", "low", "workUnits", List.of(Map.of("id", "u", "objective", "o", "dependencies", List.of(), "writeTargets", List.of("f.md"))),
                "requiredCapabilities", List.of("task-decomposition"), "constraints", List.of("n"),
                "authorization", Map.of("allowedActions", List.of("t"), "prohibitedActions", List.of("d"), "approvalRequired", List.of()), "decision", Map.of("complexity", "simple", "hasConflicts", false),
                "orchestration", Map.of("requested", true, "mcpAvailable", true));
        String digest = "sha256:" + "a".repeat(64);
        Map<String, Object> frame = arguments("schemaVersion", "1.0.0", "workspace", Map.of("workspaceId", "w", "locator", workspace),
                "controlArtifacts", List.of(Map.of("artifactId", "c", "role", "pass-condition", "locator", "c.md", "digest", digest)),
                "targetArtifacts", List.of(Map.of("artifactId", "t", "role", "target", "locator", "f.md", "digest", digest)),
                "operationalSettings", Map.of("maxAttemptsPerEpoch", 3, "maxEpochs", 2, "leaseTtlSeconds", 3600));
        JsonNode opened = ok(sennard.call("open_convergence_root", arguments("schemaVersion", "1.0.0", "parentRootId", null, "taskEnvelope", task, "frame", frame, "userApprovalRefs", List.of()), sennard.session()));
        JsonNode settings = opened.path("frame").path("operationalSettings");
        assertLimits(settings, 5, 4);
        assertEquals(3600, settings.path("leaseTtlSeconds").asInt(), "the part of the frame the setting does not own is as the caller wrote it");
        assertRootLimits(status(opened.get("rootId").asString()), 5, 4);
        // A value the contract does not allow is still refused as the caller's input.
        Map<String, Object> zero = new LinkedHashMap<>(frame);
        zero.put("operationalSettings", Map.of("maxAttemptsPerEpoch", 0, "maxEpochs", 2, "leaseTtlSeconds", 3600));
        JsonNode refused = sennard.call("open_convergence_root", arguments("schemaVersion", "1.0.0", "parentRootId", null, "taskEnvelope", task, "frame", zero, "userApprovalRefs", List.of()), sennard.session());
        assertFalse(refused.path("ok").asBoolean(true), refused.toString());
    }

    @Test
    @Order(8)
    void signingTheDefaultsAgainGivesThemToTheNextRoot() throws Exception {
        sennard.sign(owner, change(3, 2));
        JsonNode view = convergence();
        assertLimits(view.path("limits"), 3, 2);
        assertTrue(view.path("signed").asBoolean(false), "a setting that equals the default is still a signed one");
        assertRootLimits(status(startWorkflow().rootId()), 3, 2);
    }
}
