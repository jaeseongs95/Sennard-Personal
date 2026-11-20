// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.app.journal.ChangeJournalService;
import io.github.jaeseongs95.sennard.app.journal.HookBasis;
import io.github.jaeseongs95.sennard.app.journal.JournalScanner;
import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.personal.skills.TestJson;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.server.skills.SkillCheckService;
import io.github.jaeseongs95.sennard.server.skills.SkillCheckService.Outcome;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * change-scope-guardian through {@link SkillCheckService} on the running server: the run is started by {@code start_workflow} with its writeTargets and a change plan (the product's
 * wiring), so the run's change record is the real {@link ChangeJournalService} on the business database holding what the product records: the files under the writeTargets
 * ({@link #WRITE_TARGETS}), nothing else. The files change on disk and the check rescans; nothing is replaced. The check's scope rules are {@code src/**} included and
 * {@code docs/**} excluded; a file's content changes size, so a change never depends on the file system's time resolution.
 *
 * <p>What lies outside the writeTargets is not recorded, so this check does not see it (see {@link #aChangeOutsideTheWriteTargetsIsOutsideWhatThisCheckSees}). The agent's own
 * writes there are refused by the edit gate; what a build or another program changes there is seen by nothing here.
 */
@Tag("business-db")
class ChangeScopeServerTest {
    private static final String SKILL = "change-scope-guardian";
    private static final String KEY = "skillScripts.changeScope.";
    /** The run's writeTargets: the only part of the workspace that the run's change record covers. */
    private static final List<String> WRITE_TARGETS = List.of("src", "tools", "docs");
    private static ToolServerSupport sennard;
    private static SkillCheckService checks;
    private static ChangeJournalService journal;
    private static JournalScanner scanner;

    @BeforeAll
    static void start() throws Exception {
        Path repository = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(repository.resolve("contracts")) || !Files.isDirectory(repository.resolve("skills"))) {
            repository = repository.getParent();
        }
        // start_workflow needs the content root and the skills folder.
        sennard = ToolServerSupport.startWith("--sennard.content-root=" + repository, "--sennard.skills-dir=" + repository.resolve("skills"));
        checks = sennard.bean(SkillCheckService.class);
        journal = sennard.bean(ChangeJournalService.class);
        scanner = sennard.bean(JournalScanner.class);
    }

    @AfterAll
    static void stop() {
        sennard.close();
        sennard = null;
        checks = null;
        journal = null;
        scanner = null;
    }

    /** One hook observation, as the hook's recorder makes it: what the read starts from, the read outside every transaction, the write. It must read everything. */
    private static void recordHook(String workflowId, Path file) {
        HookBasis basis = journal.hookBasis(workflowId, file).orElseThrow();
        assertTrue(journal.recordHook(basis, scanner.scanHook(basis)).isEmpty());
    }

    /** A run started in a workspace: its id, and the workspace (a real path). */
    private record Run(String workflowId, Path workspace) {
        Path file(String relative) {
            return workspace.resolve(relative);
        }

        String text(String relative) {
            return file(relative).toString().replace('\\', '/');
        }
    }

    /** The files of a workspace: three folders that are writeTargets, and {@code elsewhere.txt}, which no writeTarget names. */
    private static Path workspace(Path temp) throws IOException {
        Path workspace = temp.toRealPath();
        Files.createDirectories(workspace.resolve("src"));
        Files.createDirectories(workspace.resolve("docs"));
        Files.createDirectories(workspace.resolve("tools"));
        Files.writeString(workspace.resolve("src/app.js"), "export const value = 1;\n");
        Files.writeString(workspace.resolve("docs/note.md"), "baseline\n");
        Files.writeString(workspace.resolve("elsewhere.txt"), "existing\n");
        Files.writeString(workspace.resolve("tools/planned-only.txt"), "one\n");
        Files.writeString(workspace.resolve("tools/unplanned.txt"), "existing\n");
        return workspace;
    }

    /**
     * The product's start: {@code start_workflow} with the writeTargets and, when there is a plan, the change plan (files relative to the workspace). With a plan the run is executing
     * and its baseline is the files under the writeTargets; without one it is in its planning phase and has no baseline.
     */
    private static Run start(Path workspace, List<String> writeTargets, List<String> plan) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("goal", "Change " + writeTargets + " in " + workspace.getFileName());
        arguments.put("stages", List.of("minimal-implementation"));
        arguments.put("skills", List.of(Map.of("skillId", "minimal-implementation", "reason", "test")));
        arguments.put("acceptanceCriteria", List.of("The change works."));
        arguments.put("workspace", workspace.toString());
        arguments.put("writeTargets", writeTargets);
        if (!plan.isEmpty()) {
            arguments.put("changePlan", plan);
        }
        JsonNode envelope = sennard.callAsNewHostCall("start_workflow", arguments, UUID.randomUUID().toString());
        assertTrue(envelope.path("ok").asBoolean(false), envelope::toString);
        JsonNode data = envelope.get("data");
        assertEquals(plan.isEmpty() ? "PLANNING" : "EXECUTING", data.path("changeJournal").path("phase").stringValue(), data::toString);
        assertTrue(data.path("changeJournal").path("unreadable").isEmpty(), () -> "the baseline is read in full: " + data);
        return new Run(data.get("runId").stringValue(), workspace);
    }

    /** An executing run in a new workspace, with a plan of these files. */
    private static Run run(Path temp, String... planned) throws IOException {
        return start(workspace(temp), WRITE_TARGETS, List.of(planned));
    }

    private static Run planningRun(Path temp) throws IOException {
        return start(workspace(temp), WRITE_TARGETS, List.of());
    }

    private static ObjectNode envelope() {
        List<String> included = List.of("src/**");
        return TestJson.object("schemaVersion", "1.0.0", "taskId", "task-1", "objective", "Update source", "scope", TestJson.object("included", TestJson.array(included), "excluded", TestJson.array(List.of("docs/**"))),
                "acceptanceCriteria", TestJson.array(List.of("Source is updated")), "riskLevel", "low",
                "workUnits", TestJson.array(List.of(TestJson.object("id", "unit-1", "objective", "Edit source", "dependencies", TestJson.array(List.of()), "writeTargets", TestJson.array(included)))),
                "requiredCapabilities", TestJson.array(List.of()), "constraints", TestJson.array(List.of()),
                "authorization", TestJson.object("allowedActions", TestJson.array(List.of("edit")), "prohibitedActions", TestJson.array(List.of()), "approvalRequired", TestJson.array(List.of())),
                "decision", TestJson.object("complexity", "simple", "hasConflicts", false), "orchestration", TestJson.object("requested", false, "mcpAvailable", false));
    }

    private static ObjectNode request(String mode, Run run, Path workspaceRoot, JsonNode baseline) {
        ObjectNode request = TestJson.object("schemaVersion", "1.0.0", "mode", mode, "workflowId", run.workflowId(), "taskEnvelope", envelope());
        if (workspaceRoot != null) {
            request.put("workspaceRoot", workspaceRoot.toString());
        }
        if (baseline != null) {
            request.set("baseline", baseline);
            request.put("baselineArtifactDigest", TestJson.text(baseline, "manifestSha256"));
        }
        return request;
    }

    private static Outcome call(String command, ObjectNode input) {
        return checks.run(SKILL, command, TestJson.object("input", input));
    }

    private static JsonNode value(Outcome outcome) {
        assertEquals(0, outcome.exitCode(), () -> outcome.body().toString());
        assertNotNull(outcome.body().get("value"), () -> outcome.body().toString());
        return outcome.body().get("value");
    }

    private static void refused(Outcome outcome, String key) {
        assertEquals(2, outcome.exitCode(), () -> outcome.body().toString());
        assertEquals("INVALID_INPUT", outcome.body().path("code").stringValue(), () -> outcome.body().toString());
        assertEquals(KEY + key, outcome.body().path("messageKey").stringValue(), () -> outcome.body().toString());
    }

    private static JsonNode baselineOf(Run run) {
        return value(call("capture-baseline", request("capture", run, null, null)));
    }

    private static JsonNode compare(Run run, JsonNode baseline) {
        return value(call("compare", request("verify", run, run.workspace(), baseline)));
    }

    private static JsonNode change(JsonNode report, String path) {
        return TestJson.items(report.get("changes")).stream().filter(change -> path.equals(TestJson.text(change, "path"))).findFirst().orElseThrow(() -> new AssertionError(path + " is not among " + report.get("changes")));
    }

    private static void valid(JsonNode report) {
        Outcome validated = call("validate-report", TestJson.object("artifact", report, "expectedArtifactDigest", report.get("reportSha256")));
        assertEquals(0, validated.exitCode(), () -> validated.body().toString());
    }

    /** A report as its writer signs it: the digest of the report without {@code reportSha256}. */
    private static ObjectNode signed(ObjectNode report) {
        report.remove("reportSha256");
        report.put("reportSha256", Digests.sha256Hex(CanonicalJson.of(report)));
        return report;
    }

    // ---------------------------------------------------------------------------------------------------------- classification

    @Test
    void aPlannedFileAndAFileOfAWriteScopeAreInScope(@TempDir Path temp) throws Exception {
        Run run = run(temp, "src/app.js", "tools/planned-only.txt");
        JsonNode baseline = baselineOf(run);
        Files.writeString(run.file("src/app.js"), "export const value = 22;\n");
        Files.writeString(run.file("tools/planned-only.txt"), "one and more\n");
        JsonNode report = compare(run, baseline);
        assertEquals("PASS", report.get("verdict").stringValue(), report::toString);
        assertEquals(2, report.get("summary").get("in-scope").asInt());
        assertEquals(2, report.get("changes").size());
        JsonNode app = change(report, run.text("src/app.js"));
        assertEquals("modified", app.get("status").stringValue());
        assertEquals("in-scope", app.get("classification").stringValue());
        assertEquals("src/**", app.get("matchedRule").stringValue());
        assertNotNull(TestJson.text(app, "baselineSha256"), "a planned file's baseline has its digest");
        assertNotNull(TestJson.text(app, "currentSha256"));
        JsonNode plannedOnly = change(report, run.text("tools/planned-only.txt"));
        assertEquals("in-scope", plannedOnly.get("classification").stringValue(), "the plan alone puts a file in scope");
        assertNull(TestJson.text(plannedOnly, "matchedRule"));
        assertEquals(run.workflowId(), report.get("workflowId").stringValue());
        valid(report);
    }

    @Test
    void aFileOfTheWriteTargetsOutsideThePlanAndTheScopeIsUnplanned(@TempDir Path temp) throws Exception {
        Run run = run(temp, "src/app.js");
        JsonNode baseline = baselineOf(run);
        // tools is a writeTarget of the run (so it is recorded), but it is neither planned nor named by the check's scope rules.
        Files.writeString(run.file("tools/unplanned.txt"), "existing and changed\n");
        JsonNode report = compare(run, baseline);
        assertEquals("NEEDS_REVIEW", report.get("verdict").stringValue(), report::toString);
        assertEquals(1, report.get("summary").get("unplanned").asInt());
        assertEquals(List.of("unplanned:" + run.text("tools/unplanned.txt")), TestJson.strings(report.get("findings")));
        valid(report);
        ObjectNode forgedVerdict = (ObjectNode) report.deepCopy();
        forgedVerdict.put("verdict", "PASS");
        refused(call("validate-report", TestJson.object("artifact", forgedVerdict, "expectedArtifactDigest", report.get("reportSha256"))), "verdictMismatch");
        ObjectNode forgedChanges = (ObjectNode) report.deepCopy();
        ((ObjectNode) forgedChanges.get("changes").get(0)).put("path", run.text("src/app.js"));
        // currentDigest is the digest of the changes list, so a list rewritten after the report was made no longer matches it.
        refused(call("validate-report", TestJson.object("artifact", forgedChanges, "expectedArtifactDigest", report.get("reportSha256"))), "reportDigestInvalid");
    }

    /**
     * The check's range is the run's change record, which is the files under the writeTargets. A path that no writeTarget names is not in the baseline and a change of it is not
     * recorded, so the report has no change for it: it is outside what this check sees, and no verdict here is evidence about it. (The agent's own write there is refused by the
     * edit gate; a build or another program writing there is seen by nothing in this check.)
     */
    @Test
    void aChangeOutsideTheWriteTargetsIsOutsideWhatThisCheckSees(@TempDir Path temp) throws Exception {
        Run run = run(temp, "src/app.js");
        JsonNode baseline = baselineOf(run);
        Files.writeString(run.file("elsewhere.txt"), "existing and changed\n");
        Files.writeString(run.file("outside-created.txt"), "new\n");
        JsonNode report = compare(run, baseline);
        assertTrue(TestJson.items(baseline.get("entries")).stream().noneMatch(entry -> TestJson.text(entry, "path").endsWith("/elsewhere.txt")), "the baseline holds no file outside the writeTargets");
        assertEquals(0, report.get("changes").size(), () -> "no change outside the writeTargets is recorded: " + report);
        valid(report);
    }

    @Test
    void anExcludedPathBlocksEvenWhenThePlanNamesIt(@TempDir Path temp) throws Exception {
        Run run = run(temp, "docs/note.md");
        JsonNode baseline = baselineOf(run);
        Files.writeString(run.file("docs/note.md"), "changed\n");
        JsonNode report = compare(run, baseline);
        assertEquals("BLOCKED", report.get("verdict").stringValue(), report::toString);
        assertEquals(1, report.get("summary").get("excluded").asInt());
        assertEquals("docs/**", change(report, run.text("docs/note.md")).get("matchedRule").stringValue());
        valid(report);
    }

    @Test
    void pathsOutsideTheWorkspaceRootAreUnplannedWithoutAComparisonWithTheRules(@TempDir Path temp) throws Exception {
        // A file of tools is planned (a plan has at least one file) and stays as it is, so both changed files are recorded and unplanned.
        Run run = run(temp, "tools/planned-only.txt");
        JsonNode baseline = baselineOf(run);
        Files.writeString(run.file("docs/note.md"), "changed\n");
        Files.writeString(run.file("src/app.js"), "export const value = 22;\n");
        // The scopes are relative to docs here: note.md is not "docs/**" and src/app.js is not beneath the workspace at all.
        JsonNode report = value(call("compare", request("verify", run, run.file("docs"), baseline)));
        assertEquals("NEEDS_REVIEW", report.get("verdict").stringValue(), report::toString);
        assertEquals(2, report.get("summary").get("unplanned").asInt());
        assertNull(TestJson.text(change(report, run.text("src/app.js")), "matchedRule"));
        assertNull(TestJson.text(change(report, run.text("docs/note.md")), "matchedRule"));
    }

    // ---------------------------------------------------------------------------------------------------- what the record says

    @Test
    void createdAndDeletedFilesCarryTheirStatus(@TempDir Path temp) throws Exception {
        Run run = run(temp, "src/app.js");
        JsonNode baseline = baselineOf(run);
        Files.writeString(run.file("src/created.js"), "export const created = true;\n");
        Files.delete(run.file("src/app.js"));
        JsonNode report = compare(run, baseline);
        JsonNode created = change(report, run.text("src/created.js"));
        assertEquals("created", created.get("status").stringValue());
        assertNull(TestJson.text(created, "baselineSha256"));
        assertNotNull(TestJson.text(created, "currentSha256"));
        JsonNode deleted = change(report, run.text("src/app.js"));
        assertEquals("deleted", deleted.get("status").stringValue());
        assertNotNull(TestJson.text(deleted, "baselineSha256"));
        assertNull(TestJson.text(deleted, "currentSha256"));
        assertEquals("PASS", report.get("verdict").stringValue(), report::toString);
        valid(report);
    }

    @Test
    void aPathPutBackOrCreatedAndRemovedIsNotAChange(@TempDir Path temp) throws Exception {
        Run run = run(temp, "src/app.js");
        JsonNode baseline = baselineOf(run);
        // Seen by a hook while changed, then restored: the baseline holds this planned file's digest, so the same content is the same file.
        Files.writeString(run.file("src/app.js"), "export const value = 22;\n");
        recordHook(run.workflowId(), run.file("src/app.js"));
        Files.writeString(run.file("src/app.js"), "export const value = 1;\n");
        Files.writeString(run.file("src/temporary.js"), "export const temporary = 1;\n");
        recordHook(run.workflowId(), run.file("src/temporary.js"));
        Files.delete(run.file("src/temporary.js"));
        JsonNode report = compare(run, baseline);
        assertEquals(0, report.get("changes").size(), report::toString);
        assertEquals("PASS", report.get("verdict").stringValue());
        valid(report);
    }

    @Test
    void withoutABaselineEveryChangeIsOwnershipUnknownAndTheVerdictInconclusive(@TempDir Path temp) throws Exception {
        Run run = run(temp, "src/app.js");
        Files.writeString(run.file("src/app.js"), "export const value = 22;\n");
        JsonNode report = value(call("compare", request("verify", run, run.workspace(), null)));
        assertEquals("INCONCLUSIVE", report.get("verdict").stringValue(), report::toString);
        assertEquals(1, report.get("summary").get("ownership-unknown").asInt());
        assertEquals("0".repeat(64), report.get("baselineDigest").stringValue());
        valid(report);
    }

    @Test
    void aReportThatListsAnUnreadablePathIsInconclusive(@TempDir Path temp) throws Exception {
        Run run = run(temp, "src/app.js");
        JsonNode baseline = baselineOf(run);
        Files.writeString(run.file("src/app.js"), "export const value = 22;\n");
        ObjectNode report = (ObjectNode) compare(run, baseline).deepCopy();
        // A report that lists an unreadable path is not a pass: its verdict must be INCONCLUSIVE, whatever else it found.
        ((ArrayNode) report.get("findings")).add("unreadable:" + run.text("src/unreadable.js"));
        refused(call("validate-report", TestJson.object("artifact", signed(report), "expectedArtifactDigest", report.get("reportSha256"))), "verdictMismatch");
        report.put("verdict", "INCONCLUSIVE");
        Outcome accepted = call("validate-report", TestJson.object("artifact", signed(report), "expectedArtifactDigest", report.get("reportSha256")));
        assertEquals(0, accepted.exitCode(), () -> accepted.body().toString());
    }

    // ------------------------------------------------------------------------------------------------------------ a root that is a link

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aWriteRootThatIsAJunctionIsComparedAndWhenUnscannableIsInconclusive(@TempDir Path temp) throws Exception {
        assertLinkRootIsComparedAndWhenUnscannableIsInconclusive(temp, ChangeScopeServerTest::createJunction);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aWriteRootThatIsASymbolicLinkIsComparedAndWhenUnscannableIsInconclusive(@TempDir Path temp) throws Exception {
        assertLinkRootIsComparedAndWhenUnscannableIsInconclusive(temp, Files::createSymbolicLink);
    }

    /**
     * The write root {@code src} is a link to another folder. Its files are in the baseline and a change in it is compared (a link as root used to be skipped as a whole: a baseline
     * of no file, and a PASS). When the link's target is gone the root cannot be read, and the verdict is INCONCLUSIVE with the root as an unreadable path, never PASS.
     */
    private static void assertLinkRootIsComparedAndWhenUnscannableIsInconclusive(Path temp, LinkFactory links) throws Exception {
        Path workspace = temp.toRealPath();
        Path target = Files.createDirectories(workspace.resolve("real-src"));
        Files.writeString(target.resolve("app.js"), "export const value = 1;\n");
        Path root = workspace.resolve("src");
        links.create(root, target);
        try {
            // The product's start with the link as the writeTarget: the baseline is read through the link.
            Run run = start(workspace, List.of("src"), List.of("src/app.js"));
            JsonNode baseline = baselineOf(run);
            assertEquals(1, baseline.get("entries").size(), baseline::toString);
            Files.writeString(target.resolve("app.js"), "export const value = 22;\n");
            JsonNode changed = compare(run, baseline);
            assertEquals("PASS", changed.get("verdict").stringValue(), changed::toString);
            assertEquals("modified", change(changed, run.text("src/app.js")).get("status").stringValue());
            valid(changed);
            Files.delete(target.resolve("app.js"));
            Files.delete(target);
            JsonNode unscannable = compare(run, baseline);
            assertEquals("INCONCLUSIVE", unscannable.get("verdict").stringValue(), unscannable::toString);
            assertEquals(List.of("unreadable:" + run.text("src")), TestJson.strings(unscannable.get("findings")), unscannable::toString);
            // The change the record holds from the earlier comparison stays as it was seen (modified); the file that can no longer be read is not turned into a deletion.
            assertEquals(1, unscannable.get("changes").size(), unscannable::toString);
            assertEquals("modified", change(unscannable, run.text("src/app.js")).get("status").stringValue(), "what cannot be read is not reported as deleted");
            valid(unscannable);
        } finally {
            Files.deleteIfExists(root);
        }
    }

    /** Creates {@code link} pointing at {@code target}: a symbolic link, or on Windows a junction (a symbolic link needs a privilege there). */
    @FunctionalInterface
    private interface LinkFactory {
        void create(Path link, Path target) throws IOException;
    }

    private static void createJunction(Path junction, Path target) throws IOException {
        // Java has no API to create a Windows junction; cmd is used only to prepare this test fixture.
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), target.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "junction fixture creation timed out");
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while a junction was created", interrupted);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    // ------------------------------------------------------------------------------- a baseline folder or file that a link took the place of (AGS3-D-0259)

    /**
     * A run whose writeTargets hold {@code src/x/a.txt}, {@code src/y.txt} and {@code docs/sub/d.txt} besides {@link #workspace}'s files; {@code other/} is outside every writeTarget and
     * holds the files a link is made to point at (they are not in the baseline and must never be recorded).
     */
    private static Run linkRun(Path temp) throws IOException {
        Path workspace = workspace(temp);
        Files.createDirectories(workspace.resolve("src/x"));
        Files.createDirectories(workspace.resolve("docs/sub"));
        Files.createDirectories(workspace.resolve("other"));
        Files.writeString(workspace.resolve("src/x/a.txt"), "baseline a\n");
        Files.writeString(workspace.resolve("src/y.txt"), "baseline y\n");
        Files.writeString(workspace.resolve("docs/sub/d.txt"), "baseline d\n");
        Files.writeString(workspace.resolve("other/b.txt"), "other b, there is no a.txt here\n");
        Files.writeString(workspace.resolve("other/z.txt"), "other z, different content\n");
        return start(workspace, WRITE_TARGETS, List.of("src/app.js"));
    }

    /**
     * What a comparison must say when {@code gone} (a baseline file) is no longer a path of its own because a link stands where it or its folder was: {@code gone} is deleted and is
     * judged by the write scopes like any other deletion, nothing behind the link is recorded (a link is never followed), the link itself stays reported as a skipped link and not as
     * an unreadable path, and a second comparison says the same.
     */
    private static void assertReplacedByLinkIsDeleted(Run run, JsonNode baseline, String link, String gone, String classification, String verdict) {
        JsonNode report = compare(run, baseline);
        JsonNode deleted = change(report, run.text(gone));
        assertEquals("deleted", deleted.get("status").stringValue(), report::toString);
        assertEquals(classification, deleted.get("classification").stringValue(), report::toString);
        assertEquals(1, report.get("changes").size(), () -> "nothing behind the link is recorded: " + report);
        assertEquals(verdict, report.get("verdict").stringValue(), report::toString);
        assertTrue(TestJson.strings(report.get("findings")).stream().noneMatch(finding -> finding.startsWith("unreadable:")), () -> "a skipped link is not an unreadable path: " + report);
        List<JournalScanner.ReadFailure> failures = journal.changes(run.workflowId()).failures();
        assertTrue(failures.stream().anyMatch(failure -> failure.path().equals(run.file(link)) && failure.reason() == JournalScanner.FailureReason.LINK_OR_SPECIAL_SKIPPED),
                () -> "the link is reported as skipped: " + failures);
        valid(report);
        JsonNode again = compare(run, baseline);
        assertEquals(report.get("changes"), again.get("changes"), "the same comparison again says the same");
        assertEquals(verdict, again.get("verdict").stringValue(), again::toString);
    }

    /** The folder {@code folder} of the baseline holds only {@code file}: both are deleted, and {@code links} puts a link to {@code other/} in the folder's place. */
    private static void replaceFolderByLink(Run run, LinkFactory links, String folder, String file) throws IOException {
        Files.delete(run.file(file));
        Files.delete(run.file(folder));
        links.create(run.file(folder), run.file("other"));
    }

    /** C1, the control: the baseline folder is simply deleted. */
    @Test
    void aDeletedBaselineFolderIsReportedAsDeleted(@TempDir Path temp) throws Exception {
        Run run = linkRun(temp);
        JsonNode baseline = baselineOf(run);
        assertTrue(TestJson.items(baseline.get("entries")).stream().anyMatch(entry -> run.text("src/x/a.txt").equals(TestJson.text(entry, "path"))), baseline::toString);
        Files.delete(run.file("src/x/a.txt"));
        Files.delete(run.file("src/x"));
        JsonNode report = compare(run, baseline);
        assertEquals("deleted", change(report, run.text("src/x/a.txt")).get("status").stringValue(), report::toString);
        assertEquals("in-scope", change(report, run.text("src/x/a.txt")).get("classification").stringValue(), report::toString);
        assertEquals(1, report.get("changes").size(), report::toString);
        assertEquals("PASS", report.get("verdict").stringValue(), report::toString);
        valid(report);
    }

    /** C2: a junction to another folder takes the place of a baseline folder that is deleted; the folder's file is deleted and the junction is a skipped link. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aJunctionInThePlaceOfABaselineFolderLeavesItsFileDeleted(@TempDir Path temp) throws Exception {
        Run run = linkRun(temp);
        JsonNode baseline = baselineOf(run);
        replaceFolderByLink(run, ChangeScopeServerTest::createJunction, "src/x", "src/x/a.txt");
        try {
            assertReplacedByLinkIsDeleted(run, baseline, "src/x", "src/x/a.txt", "in-scope", "PASS");
        } finally {
            Files.deleteIfExists(run.file("src/x"));
        }
    }

    /** The same in a folder the scope excludes: the deletion is an excluded change, so the verdict is BLOCKED and not a PASS. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aJunctionInThePlaceOfAnExcludedBaselineFolderBlocks(@TempDir Path temp) throws Exception {
        Run run = linkRun(temp);
        JsonNode baseline = baselineOf(run);
        replaceFolderByLink(run, ChangeScopeServerTest::createJunction, "docs/sub", "docs/sub/d.txt");
        try {
            assertReplacedByLinkIsDeleted(run, baseline, "docs/sub", "docs/sub/d.txt", "excluded", "BLOCKED");
        } finally {
            Files.deleteIfExists(run.file("docs/sub"));
        }
    }

    /** The POSIX counterpart of C2: a symbolic link to another folder takes the place of a baseline folder. */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aSymbolicLinkInThePlaceOfABaselineFolderLeavesItsFileDeleted(@TempDir Path temp) throws Exception {
        Run run = linkRun(temp);
        JsonNode baseline = baselineOf(run);
        replaceFolderByLink(run, Files::createSymbolicLink, "src/x", "src/x/a.txt");
        try {
            assertReplacedByLinkIsDeleted(run, baseline, "src/x", "src/x/a.txt", "in-scope", "PASS");
        } finally {
            Files.deleteIfExists(run.file("src/x"));
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aSymbolicLinkInThePlaceOfAnExcludedBaselineFolderBlocks(@TempDir Path temp) throws Exception {
        Run run = linkRun(temp);
        JsonNode baseline = baselineOf(run);
        replaceFolderByLink(run, Files::createSymbolicLink, "docs/sub", "docs/sub/d.txt");
        try {
            assertReplacedByLinkIsDeleted(run, baseline, "docs/sub", "docs/sub/d.txt", "excluded", "BLOCKED");
        } finally {
            Files.deleteIfExists(run.file("docs/sub"));
        }
    }

    /** A baseline file is deleted and a symbolic link to another file takes its place (a symbolic link to a file needs no privilege on Linux and macOS, and an ordinary Windows account lacks it). */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aSymbolicLinkInThePlaceOfABaselineFileLeavesTheFileDeleted(@TempDir Path temp) throws Exception {
        Run run = linkRun(temp);
        JsonNode baseline = baselineOf(run);
        Files.delete(run.file("src/y.txt"));
        Files.createSymbolicLink(run.file("src/y.txt"), run.file("other/z.txt"));
        try {
            assertReplacedByLinkIsDeleted(run, baseline, "src/y.txt", "src/y.txt", "in-scope", "PASS");
        } finally {
            Files.deleteIfExists(run.file("src/y.txt"));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- refusals

    @Test
    void aWorkflowWithoutARecordOrStillPlanningIsRefused(@TempDir Path temp) throws Exception {
        Run unknown = new Run("run-" + UUID.randomUUID(), temp.toRealPath());
        refused(call("capture-baseline", request("capture", unknown, null, null)), "journalMissing");
        refused(call("compare", request("verify", unknown, unknown.workspace(), null)), "journalMissing");
        Run planning = planningRun(temp);
        refused(call("capture-baseline", request("capture", planning, null, null)), "journalPlanning");
        refused(call("compare", request("verify", planning, planning.workspace(), null)), "journalPlanning");
    }

    @Test
    void aBaselineThatIsNotTheRecordsOwnIsRefused(@TempDir Path temp, @TempDir Path otherTemp) throws Exception {
        Run run = run(temp, "src/app.js");
        JsonNode baseline = baselineOf(run);
        ObjectNode copied = (ObjectNode) baseline.deepCopy();
        ((ObjectNode) copied.get("entries").get(0)).put("sizeBytes", copied.get("entries").get(0).get("sizeBytes").asLong() + 1);
        refused(call("compare", request("verify", run, run.workspace(), copied)), "baselineChecksum");
        // Signed again, the document is whole but is not what the run's record holds.
        ObjectNode resigned = copied.deepCopy();
        resigned.remove("manifestSha256");
        resigned.put("manifestSha256", Digests.sha256Hex(CanonicalJson.of(TestJson.object("workflowId", run.workflowId(), "planRevision", resigned.get("planRevision"), "taskEnvelopeDigest", resigned.get("taskEnvelopeDigest"),
                "entries", resigned.get("entries"), "capturedAt", resigned.get("capturedAt")))));
        refused(call("compare", request("verify", run, run.workspace(), resigned)), "baselineRecordMismatch");
        refused(call("capture-baseline", request("capture", run, null, resigned)), "baselineRecordMismatch");
        // Another run's baseline.
        Run other = run(otherTemp, "src/app.js");
        refused(call("compare", request("verify", run, run.workspace(), baselineOf(other))), "baselineIdentityMismatch");
    }

    @Test
    void aRequestThatBreaksTheContractIsRefused(@TempDir Path temp) throws Exception {
        Run run = run(temp, "src/app.js");
        ObjectNode withoutRoot = request("verify", run, null, null);
        refused(call("compare", withoutRoot), "schemaFailed");
        refused(call("compare", request("verify", run, Path.of("relative/folder"), null)), "workspaceRootInvalid");
        ObjectNode escaping = request("capture", run, null, null);
        ((ArrayNode) escaping.get("taskEnvelope").get("scope").get("included")).add("../outside/**");
        refused(call("capture-baseline", escaping), "ruleUnsafe");
        refused(call("capture-baseline", request("verify", run, run.workspace(), null)), "requestMode");
    }
}
