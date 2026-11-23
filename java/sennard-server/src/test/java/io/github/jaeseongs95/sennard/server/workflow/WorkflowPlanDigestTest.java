// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.service.store.RunState;
import tools.jackson.databind.json.JsonMapper;

class WorkflowPlanDigestTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Path WORKSPACE = Path.of("workspace").toAbsolutePath();
    private static final Path FIRST = WORKSPACE.resolve("src/first.txt");
    private static final Path SECOND = WORKSPACE.resolve("src/second.txt");

    @Test
    void identityAndProgressDoNotChangeTheDigest() {
        String before = digest(task("first", "Update files", "Build passes", "English observation"),
                plan("first", "signed-first", RunState.RUNNING, "minimal-implementation"), Set.of(FIRST, SECOND), List.of("src", "tests"), "raw target");
        String after = digest(task("second", "Update files", "Build passes", "다른 관측"),
                plan("second", "signed-second", RunState.BLOCKED, "minimal-implementation"),
                Set.of(SECOND, WORKSPACE.resolve("src/nested/../first.txt")), List.of("tests", "src", "src"), "raw target");
        assertEquals(before, after);
    }

    @Test
    void equivalentRelativeFilesHaveTheSameDigestAcrossFileSystemProviders(@TempDir Path temporary) throws IOException {
        TaskEnvelope task = task("id", "Update files", "Build passes", "constraint");
        WorkflowPlan plan = plan("id", "signed", RunState.RUNNING, "minimal-implementation");
        String local = digest(task, plan, Set.of(FIRST, SECOND), List.of("src"), null);
        try (FileSystem zip = FileSystems.newFileSystem(temporary.resolve("paths.zip"), Map.of("create", "true"))) {
            Path workspace = zip.getPath("/workspace");
            Set<Path> files = Set.of(workspace.resolve("src/second.txt"), workspace.resolve("src/nested/../first.txt"));
            assertEquals(local, WorkflowPlanDigest.of(MAPPER, task, plan, workspace, files, List.of("src"), null));
        }
    }

    @Test
    void preservesPosixRootsInCanonicalPaths(@TempDir Path temporary) throws IOException {
        try (FileSystem zip = FileSystems.newFileSystem(temporary.resolve("paths.zip"), Map.of("create", "true"))) {
            assertEquals("/", WorkflowPlanDigest.canonicalPath(zip.getPath("/")));
            assertEquals("/outside/src/first.txt", WorkflowPlanDigest.canonicalPath(zip.getPath("/outside/src/first.txt")));
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void preservesDriveAndShareRootsInCanonicalPaths() {
        assertEquals("C:/", WorkflowPlanDigest.canonicalPath(Path.of("C:\\")));
        assertEquals("C:/outside/first.txt", WorkflowPlanDigest.canonicalPath(Path.of("C:\\outside\\first.txt")));
        assertEquals("//server/share/", WorkflowPlanDigest.canonicalPath(Path.of("\\\\server\\share\\")));
        assertEquals("//server/share/first.txt", WorkflowPlanDigest.canonicalPath(Path.of("\\\\server\\share\\first.txt")));
    }

    @Test
    void submittedPlanChangesProduceNewDigests() {
        TaskEnvelope task = task("id", "Update files", "Build passes", "constraint");
        WorkflowPlan plan = plan("id", "signed", RunState.RUNNING, "minimal-implementation");
        String original = digest(task, plan, Set.of(FIRST), List.of("src"), "raw target");
        assertNotEquals(original, digest(task("id", "Fix files", "Build passes", "constraint"), plan, Set.of(FIRST), List.of("src"), "raw target"));
        assertNotEquals(original, digest(task("id", "Update files", "Tests pass", "constraint"), plan, Set.of(FIRST), List.of("src"), "raw target"));
        assertNotEquals(original, digest(task, plan, Set.of(SECOND), List.of("src"), "raw target"));
        assertNotEquals(original, digest(task, plan, Set.of(FIRST), List.of("tests"), "raw target"));
        assertNotEquals(original, digest(task, plan, Set.of(FIRST), List.of("src"), "revised target"));
        assertNotEquals(original, digest(task, plan("id", "signed", RunState.RUNNING, "test-engineering"), Set.of(FIRST), List.of("src"), "raw target"));
        assertNotEquals(digest(task, plan, Set.of(FIRST), List.of("src"), null),
                digest(task("id", "Update files", "Build passes", "other constraint"), plan, Set.of(FIRST), List.of("src"), null));
    }

    private static TaskEnvelope task(String id, String objective, String criterion, String constraint) {
        return new TaskEnvelope("1.0.0", id, objective, new TaskEnvelope.Scope(List.of("src"), List.of()), List.of(criterion), null, List.of(), null,
                List.of(), List.of(constraint), new TaskEnvelope.Authorization(List.of(), List.of(), List.of()), null, null);
    }

    private static WorkflowPlan plan(String id, String token, RunState state, String skill) {
        return new WorkflowPlan("1.0.0", id, "digest-" + id, token, null, state, List.of(skill), List.of(),
                state == RunState.RUNNING ? "stage-1" : null, null, List.of(), null);
    }

    private static String digest(TaskEnvelope task, WorkflowPlan plan, Set<Path> files, List<String> scope, String target) {
        return WorkflowPlanDigest.of(MAPPER, task, plan, WORKSPACE, files, scope, target);
    }
}
