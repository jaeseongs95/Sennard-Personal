// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static io.github.jaeseongs95.sennard.server.message.MessageRef.msg;
import static io.github.jaeseongs95.sennard.server.workflow.WorkflowFailure.details;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where the paths of a root's write surface physically live. Read from the file system only, without a child process. It reads the file system (real paths), so a
 * use case does not call it inside its transaction: the entry resolves before the transaction ({@link WorkflowFileReader}) and the use case judges with the answer
 * ({@link ConvergenceLogic.IdentityResolver}). Every location is a {@link Path}: whether two locations are the same, or one lies below the other, is decided by
 * the file system's own {@code Path.equals} and {@code Path.startsWith} (JDK: "depends on the file system implementation. In some cases the paths are
 * compared without regard to case"), never by a string this class would have to fold itself. No version control system is consulted: two roots conflict
 * when their physical locations overlap, whichever checkout, clone or link the locations were reached through.
 */
final class WorkspaceIdentity {
    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceIdentity.class);

    /** One write-surface entry as resolved on this host: {@code conservative} names how a glob entry was widened to a location, null for a plain path. */
    record Surface(String entry, Path physical, String conservative) {
    }

    record Identity(String host, Path workspacePhysical, List<Surface> surfaces) {
        Identity withHost(String value) {
            return new Identity(value, workspacePhysical, surfaces);
        }
    }

    private static final int WALK_LIMIT = 256;
    private static final Pattern URI_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]+://");
    private static final String GLOB_META = "*?[]{}";
    private static final String WALK_LIMIT_CAUSE = "walk limit reached";

    private WorkspaceIdentity() {
    }

    private static boolean isUnsupported(String entry) {
        if (URI_PATTERN.matcher(entry).find()) {
            return true;
        }
        return entry.chars().anyMatch(c -> c <= 0x1f || c == 0x7f);
    }

    static void assertSupportedScopeEntry(String entry) {
        if (isUnsupported(entry)) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("services.workspace.unsupportedScopeEntry"), details("reason", "UNSUPPORTED_SCOPE_ENTRY", "entry", entry));
        }
    }

    /** True when {@code child} equals {@code parent} or lies below it. */
    static boolean pathWithin(Path child, Path parent) {
        return child.startsWith(parent);
    }

    private static WorkflowFailure unresolved(String target, String cause) {
        return unresolved(target, cause, null);
    }

    /** {@code cause} is the sentence's word for it; the exception that made it so, when there is one, stays attached. */
    private static WorkflowFailure unresolved(String target, String cause, Throwable thrown) {
        return new WorkflowFailure(WorkflowCode.INVALID_INPUT,
                WALK_LIMIT_CAUSE.equals(cause) ? msg("services.workspace.unresolvedWalkLimit", "target", target) : msg("services.workspace.unresolved", "target", target, "cause", cause),
                details("reason", "WORKSPACE_IDENTITY_UNRESOLVED", "path", target, "cause", cause), thrown);
    }

    private static boolean noEntry(IOException error) {
        return error instanceof NoSuchFileException || error instanceof NotDirectoryException
                || (error instanceof FileSystemException fs && fs.getReason() != null && fs.getReason().contains("Not a directory"));
    }

    private static String code(Exception error) {
        return error.getClass().getSimpleName();
    }

    private static Path path(String target) {
        try {
            return Path.of(target);
        } catch (InvalidPathException cause) {
            throw unresolved(target, "invalid path", cause);
        }
    }

    /** Real path of the nearest existing ancestor, plus the suffix that does not exist yet. */
    private static Path physicalPath(String target) {
        Path current = path(target).toAbsolutePath().normalize();
        Deque<Path> suffix = new ArrayDeque<>();
        for (int depth = 0; depth < WALK_LIMIT; depth++) {
            try {
                Path real = current.toRealPath();
                for (Path segment : suffix) {
                    real = real.resolve(segment);
                }
                return real;
            } catch (IOException error) {
                Path parent = current.getParent();
                // Only "no such path yet" walks up; denial, loops and the like mean "cannot tell".
                if (!noEntry(error) || parent == null) {
                    throw unresolved(target, code(error), error);
                }
                // A dangling link is followed: what gets created through it lands at its target, not beside the link.
                Path link = null;
                if (Files.isSymbolicLink(current)) {
                    try {
                        link = Files.readSymbolicLink(current);
                    } catch (IOException cause) {
                        if (!noEntry(cause)) {
                            throw unresolved(target, code(cause), cause);
                        }
                    }
                }
                if (link != null) {
                    // Each component resolves physically while normalize() folds `..` lexically. Leading `..` are taken from the physical
                    // parent, so they agree; a `..` after a name could climb out of a linked directory, so it is refused.
                    boolean named = false;
                    for (Path segment : link) {
                        String name = segment.toString();
                        if ("..".equals(name) && named) {
                            throw unresolved(target, "dangling link climbs after a name");
                        }
                        named |= !name.isEmpty() && !".".equals(name) && !"..".equals(name);
                    }
                    try {
                        current = (link.isAbsolute() ? link : parent.toRealPath().resolve(link)).normalize();
                    } catch (IOException cause) {
                        throw unresolved(target, code(cause), cause);
                    }
                    continue;
                }
                suffix.addFirst(current.getFileName());
                current = parent;
            }
        }
        throw unresolved(target, WALK_LIMIT_CAUSE);
    }

    /** The physical location of one path, as a root surface resolves it. */
    static Path identify(String target) {
        return physicalPath(target);
    }

    private static Path identifyPhysicalOnly(String target) {
        try {
            return physicalPath(target);
        } catch (WorkflowFailure unreadable) {
            LOG.debug("The location {} is taken as written", target, unreadable);
            return Path.of(target).toAbsolutePath().normalize();
        }
    }

    static boolean sameHost(String left, String right) {
        return left == null || right == null || Objects.equals(left, right);
    }

    /** The index just past the last separator before the first glob character, or -1 when the entry's first segment is already a glob. */
    private static int literalPrefixEnd(String entry) {
        int meta = -1;
        for (int i = 0; i < entry.length() && meta < 0; i++) {
            if (GLOB_META.indexOf(entry.charAt(i)) >= 0) {
                meta = i;
            }
        }
        if (meta < 0) {
            return entry.length();
        }
        // A scope entry is portable text: "/" and "\" both end a segment on every operating system (the answer does not depend on which one the server runs on).
        for (int i = meta - 1; i >= 0; i--) {
            if (entry.charAt(i) == '/' || entry.charAt(i) == '\\') {
                return i + 1;
            }
        }
        return -1;
    }

    /** {@code physicalOnly}: a location that cannot be read is taken as written instead of refused. */
    static Identity resolveRootIdentity(String workspaceLocator, List<String> entries, boolean physicalOnly) {
        entries.forEach(WorkspaceIdentity::assertSupportedScopeEntry);
        Function<String, Path> surfaceOf = physicalOnly ? WorkspaceIdentity::identifyPhysicalOnly : WorkspaceIdentity::identify;
        Path workspace = surfaceOf.apply(workspaceLocator);
        List<Surface> surfaces = new ArrayList<>();
        for (String entry : entries) {
            int prefixEnd = literalPrefixEnd(entry);
            if (prefixEnd < 0) {
                surfaces.add(new Surface(entry, workspace, "leading-glob"));
                continue;
            }
            boolean glob = prefixEnd < entry.length();
            // The trailing separator keeps a bare drive or root prefix ("C:", "") absolute.
            String target = glob ? entry.substring(0, prefixEnd) : entry;
            surfaces.add(new Surface(entry, surfaceOf.apply(path(workspaceLocator).resolve(path(target)).normalize().toString()), glob ? "glob-prefix" : null));
        }
        return new Identity(null, workspace, surfaces);
    }
}
