// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static io.github.jaeseongs95.sennard.server.message.MessageRef.msg;
import static io.github.jaeseongs95.sennard.server.workflow.WorkflowFailure.details;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Where a run's files are, for its change journal: the write roots its writeTargets name in the workspace, and the plan files checked against them. Only the paths are
 * worked out here (no file is read), so it can run inside the start's transaction.
 *
 * <p>A write target is a path relative to the workspace, or an absolute one. One with a glob character keeps only the directories in front of the first element that has
 * one (a target {@code src}, then a recursive glob, is the root {@code src}); a target that starts with a glob element is the whole workspace.
 */
@Component
class ChangeJournalScope {
    private static final Logger LOG = LoggerFactory.getLogger(ChangeJournalScope.class);
    private static final Pattern GLOB = Pattern.compile("[*?\\[\\]{}]");
    private static final Pattern SEPARATORS = Pattern.compile("[/\\\\]+");

    /** The workspace of a run and the roots of its writeTargets in it, none of them inside another. */
    record Scope(Path workspace, List<Path> roots) {
    }

    Scope scope(String workspace, List<String> writeTargets) {
        Path base = absolute(workspace, "workspace");
        List<Path> found = new ArrayList<>();
        for (String target : writeTargets) {
            found.add(root(base, target));
        }
        List<Path> roots = new ArrayList<>();
        for (Path candidate : new LinkedHashSet<>(found)) {
            if (found.stream().noneMatch(other -> !other.equals(candidate) && candidate.startsWith(other))) {
                roots.add(candidate);
            }
        }
        return new Scope(base, List.copyOf(roots));
    }

    /** The plan's files as absolute paths; a path outside every write root is the caller's input error, named by {@code field}. */
    Set<Path> plan(Scope scope, List<String> files, String field) {
        Set<Path> plan = new LinkedHashSet<>();
        for (String file : files) {
            Path path = resolved(scope.workspace(), file, field);
            if (scope.roots().stream().noneMatch(path::startsWith)) {
                throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.journal.planOutsideWriteTargets", "field", field, "path", file), details("field", field, "reason", "PLAN_OUTSIDE_WRITE_TARGETS", "path", file));
            }
            plan.add(path);
        }
        return plan;
    }

    private static Path root(Path workspace, String target) {
        Path absolute = absoluteOrNull(target.strip());
        if (absolute != null) {
            return absolute.normalize();
        }
        Path root = workspace;
        for (String part : SEPARATORS.split(target.strip())) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if (GLOB.matcher(part).find()) {
                break;
            }
            root = root.resolve(part);
        }
        return root.normalize();
    }

    private static Path resolved(Path workspace, String file, String field) {
        try {
            return workspace.resolve(Path.of(file.strip())).normalize();
        } catch (InvalidPathException invalid) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.journal.planPathInvalid", "field", field, "path", file), details("field", field, "reason", "PLAN_PATH_INVALID", "path", file), invalid);
        }
    }

    /** The JDK has no way to ask whether a text is a path of this file system other than building one; a text with a glob character is none on Windows. */
    private static Path absoluteOrNull(String text) {
        try {
            Path path = Path.of(text);
            return path.isAbsolute() ? path : null;
        } catch (InvalidPathException notAPath) {
            LOG.debug("The text names no absolute path on this file system", notAPath);
            return null;
        }
    }

    private static Path absolute(String text, String field) {
        Path path = absoluteOrNull(text);
        if (path == null) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.workspaceAbsolutePath"), details("field", field));
        }
        return path.normalize();
    }
}
