// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.continuity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.github.jaeseongs95.sennard.service.store.RootState;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where a path is, compared with the write surfaces of the active workflow roots.
 *
 * <p>Paths are compared as {@link Path}s, never as text: whether two spellings name the same place (case, separators, {@code ..}) is the file
 * system's own rule ({@link Path#equals}, {@link Path#startsWith}: "depends on the file system implementation", JDK 25 {@code Path}). The strings a
 * stored root holds are read back into {@link Path}s before they are compared, whatever spelling they were written in.
 *
 * <p>ceiling: a root's {@code host} is not compared, so a root of another PC's checkout at the same path counts as this one's; the server
 * has no host identity of its own yet. A store shared between PCs needs that comparison back.
 */
@Component
public class SurfaceIdentity {
    private static final Logger LOG = LoggerFactory.getLogger(SurfaceIdentity.class);

    /** One active root with the physical places of its surfaces. */
    public record ActiveRoot(String rootId, RootState state, List<Path> surfaces) {
        /** Inside a surface's physical path: {@link Path#startsWith(Path)} compares whole name elements, so {@code /ab} is not within {@code /a}; an empty surface contains everything. */
        boolean covers(Path physical) {
            return surfaces.stream().anyMatch(surface -> surface.toString().isEmpty() || physical.startsWith(surface));
        }
    }

    /** The part of a stored root document that is read here. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RootDocument(RootState state) {
    }

    /** The part of a stored identity document that is read here: the physical place of each write surface. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record IdentityDocument(List<SurfaceDocument> surfaces) {
        IdentityDocument {
            surfaces = surfaces == null ? List.of() : surfaces;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SurfaceDocument(String physical) {
    }

    private final WorkflowStore workflows;
    private final JsonMapper mapper;

    public SurfaceIdentity(WorkflowStore workflows, JsonMapper mapper) {
        this.workflows = workflows;
        this.mapper = mapper;
    }

    List<ActiveRoot> activeRoots() {
        List<ActiveRoot> roots = new ArrayList<>();
        for (WorkflowStore.ActiveRootRow row : workflows.readActiveRoots()) {
            RootState state = mapper.readValue(row.rootJson(), RootDocument.class).state();
            if (state == null) {
                throw new IllegalStateException("A stored workflow root names no state");
            }
            List<Path> surfaces = new ArrayList<>();
            if (row.identityJson() != null) {
                for (SurfaceDocument surface : mapper.readValue(row.identityJson(), IdentityDocument.class).surfaces()) {
                    surfaces.add(Path.of(surface.physical() == null ? "" : surface.physical()));
                }
            }
            roots.add(new ActiveRoot(row.rootId(), state, surfaces));
        }
        return roots;
    }

    /** The physical place of one path; a path that cannot be read at all (an edit tool's unread target) has none and only counts in the window. */
    Path identify(String given) {
        Path path;
        try {
            path = Path.of(given);
        } catch (InvalidPathException notAPath) {
            // The JDK has no way to ask whether a text is a path other than building one.
            LOG.debug("The text given as an edit target is no path on this file system", notAPath);
            return null;
        }
        if (!path.isAbsolute()) {
            return null;
        }
        return physical(path);
    }

    /**
     * The real path of the deepest existing ancestor with the unresolved tail appended, so a new file has a stable key too. Two spellings of one
     * place ({@code ..}, a link, another case on a case-insensitive file system) come out as the same path when the place exists.
     *
     * <p>The spelling is asked of the file system as it is given and {@code ..} is never removed as text: whether {@code a/b/../c} is {@code a/c} when {@code a/b} does not
     * exist is the file system's own rule (the JDK resolves it on one, and not on another), and a key made by erasing it would call two places one that the file system
     * calls two ({@link Files#isSameFile}). A {@code ..} that lies in the tail, below the deepest ancestor that exists, therefore stays in the key as written.
     */
    static Path physical(Path path) {
        Path existing = path;
        List<Path> tail = new ArrayList<>();
        while (existing != null && !Files.exists(existing)) {
            tail.addFirst(existing.getFileName());
            existing = existing.getParent();
        }
        try {
            Path real = existing == null ? path : existing.toRealPath();
            for (Path name : tail) {
                real = real.resolve(name);
            }
            return real;
        } catch (IOException unreadable) {
            LOG.debug("The real path of an edit target cannot be read; the path as given stands for it", unreadable);
            return path;
        }
    }
}
