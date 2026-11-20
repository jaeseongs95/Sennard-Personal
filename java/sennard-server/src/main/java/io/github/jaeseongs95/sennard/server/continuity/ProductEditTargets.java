// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.continuity;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.PatternSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

import io.github.jaeseongs95.sennard.server.context.AdminLog;
import io.github.jaeseongs95.sennard.server.context.ContextLayerService;
import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts;
import io.github.jaeseongs95.sennard.server.hook.ShellPaths;
import io.github.jaeseongs95.sennard.server.hook.ToolCall;
import io.github.jaeseongs95.sennard.server.message.MessageRef;
import io.github.jaeseongs95.sennard.server.message.Messages;
import tools.jackson.core.JacksonException;

/**
 * Which files of an edit count for the edit gate: every file except those under the OS temporary directory, the host's own notes or the exempt directories
 * the user declared. Whether a folder is a checkout of some version control system is not asked. A tool whose target cannot be read counts as
 * one more file, so a read failure never opens the gate.
 *
 * <p>The exempt directories are the user's, and only the list signed on the admin page with a passkey ({@link ContextLayerService#verifiedState()}) names
 * them: an agent has no tool that changes it and no setting of the environment or the command line adds one. A list that cannot be read adds nothing.
 * An entry is a path of this file system; an entry that has a root but no drive ({@code /tmp} on Windows) is a path of the Git Bash installation's own
 * root, compared as text with the writes of that shell.
 *
 * <p>Paths are compared as {@link Path}s: whether two spellings name one place (case, separators, {@code ..}) is the file system's own rule
 * ({@link Path#equals}, {@link Path#startsWith}), and a file is keyed by its real place ({@link SurfaceIdentity#physical}), so the same file under two
 * spellings is one file of the window. Every match is on whole path elements.
 */
@Component
class ProductEditTargets {
    private static final Logger LOG = LoggerFactory.getLogger(ProductEditTargets.class);
    private static final String TEMPORARY_DIRECTORY = "java.io.tmpdir";
    private static final String WILDCARD = "*";

    /**
     * @param product key: the real place of the file as text, value: the path as shown in a refusal
     * @param unread the keys of {@code product} that stand for a write whose target could not be read, not for a file
     * @param unreadPlaces the absolute folders those unread writes happened in (the folders a shell followed to, the working directory for a call that names no folder); empty when
     *            some unread write has no folder this server knows, so that no workflow covers it
     */
    record Read(Map<String, String> product, Set<String> unread, List<String> unreadPlaces) {
    }

    /**
     * The exempt entries of the user, split by how they are compared: as files of this file system, as text of the Git Bash installation's own root, or as glob
     * patterns of this file system (kept as the pattern text: a pattern is no path).
     */
    record Declared(List<String> files, List<String> shellRooted, List<String> globs) {
    }

    private final Messages messages;
    private final ContextLayerService layers;
    private final String temporaryDirectory;
    private final ShellPaths shellPaths;

    ProductEditTargets(Messages messages, ContextLayerService layers, Environment environment, ShellPaths shellPaths) {
        this.messages = messages;
        this.layers = layers;
        this.shellPaths = shellPaths;
        this.temporaryDirectory = environment.getRequiredProperty(TEMPORARY_DIRECTORY);
    }

    Read read(HostContext context, ToolCall tool, HostWorkFacts.EditTargets targets, HostWorkFacts host) {
        List<Path> resolved = resolved(context.workspace(), targets.paths());
        Declared declared = declared();
        // A path of the Git Bash root has no file to look for: it is left out only when the user declared a directory that holds it.
        boolean unreadRooted = targets.shellRooted().stream().anyMatch(path -> declared.shellRooted().stream().noneMatch(root -> shellPaths.within(path, root)));
        boolean nothingNamed = targets.paths().isEmpty() && targets.shellRooted().isEmpty();
        boolean unreadWrite = targets.incomplete() || unreadRooted || nothingNamed || (!targets.paths().isEmpty() && resolved.isEmpty());
        // Where an unread write happened: the folder the shell followed to. A write that no shell placed (an edit tool whose file is not named, a path that is no path, a path of
        // the Git Bash root) is at the hook's working directory, as before.
        boolean unplacedByWorkspace = unreadRooted || (nothingNamed && !targets.incomplete()) || (!targets.paths().isEmpty() && resolved.isEmpty());
        List<String> places = unreadWrite ? unreadPlaces(context.workspace(), targets, unplacedByWorkspace) : List.of();
        Map<String, String> product = new LinkedHashMap<>();
        Set<String> unread = new LinkedHashSet<>();
        if (resolved.isEmpty()) {
            if (unreadWrite) {
                unread.add(addUnread(product, tool));
            }
            return new Read(product, unread, places);
        }
        List<Predicate<Path>> exempt = exemptions(host, declared);
        Map<Path, String> counted = new LinkedHashMap<>();
        for (Path target : resolved) {
            // The file is keyed by the place it really is: a path that goes through a link (a junction, a symbolic link) to another file is that file.
            Path place = SurfaceIdentity.physical(target);
            if (counted.containsKey(place) || exempt.stream().anyMatch(root -> root.test(place))) {
                continue;
            }
            counted.put(place, target.toString());
        }
        counted.forEach((place, shown) -> product.put(place.toString(), shown));
        if (unreadWrite) {
            unread.add(addUnread(product, tool));
        }
        return new Read(product, unread, places);
    }

    /**
     * The absolute folders of the unread writes of a call: each folder a shell followed to ({@code unreadFolders}, absolute or relative to {@code workspace}) and, when
     * {@code atWorkspace}, the working directory itself. Empty when a place cannot be made one: a shell that is in a folder it could not follow to, or a relative folder with no
     * working directory to start from. An empty answer is never covered by a workflow, so a folder the observation cannot follow does not open the gate.
     */
    static List<String> unreadPlaces(String workspace, HostWorkFacts.EditTargets targets, boolean atWorkspace) {
        if (targets.unreadUnplaced()) {
            return List.of();
        }
        List<String> folders = new ArrayList<>(targets.unreadFolders());
        if (atWorkspace) {
            folders.add("");
        }
        Set<String> places = new LinkedHashSet<>();
        for (String folder : folders) {
            Optional<Path> place = placeOf(workspace, folder);
            if (place.isEmpty()) {
                return List.of();
            }
            places.add(place.get().normalize().toString());
        }
        return new ArrayList<>(places);
    }

    /** The folder as a path: a named folder resolved against the working directory, the empty name the working directory itself; empty when there is no such place. */
    private static Optional<Path> placeOf(String workspace, String folder) {
        if (!folder.isBlank()) {
            return resolved(workspace, List.of(folder)).stream().findFirst();
        }
        if (workspace == null || workspace.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(pathOrNull(workspace));
    }

    /** What the user declared; an unreadable list declares nothing, so a read failure never opens the gate. */
    private Declared declared() {
        List<String> signed;
        try {
            AdminLog.EditExempt list = layers.verifiedState().editExempt;
            signed = list == null ? List.of() : list.paths();
        } catch (DataAccessException | TransactionException | JacksonException | IllegalStateException unreadable) {
            LOG.warn("The signed list of exempt directories cannot be read; it declares nothing", unreadable);
            return new Declared(List.of(), List.of(), List.of());
        }
        return classify(signed, shellPaths);
    }

    /**
     * Sorts the entries: one with a glob character is a pattern and stays text; an absolute path ({@link Path#isAbsolute()}) is a file path; the Git Bash
     * spelling of a drive path is that drive path; a path of the shell's own root is the shell's; a relative entry names no place and is left out.
     *
     * @param shellPaths how the shell spells the paths that are no path of this file system
     */
    static Declared classify(List<String> signed, ShellPaths shellPaths) {
        List<String> files = new ArrayList<>();
        List<String> rooted = new ArrayList<>();
        List<String> globs = new ArrayList<>();
        for (String entry : signed) {
            if (AdminLog.isGlob(entry)) {
                globs.add(entry);
                continue;
            }
            Path path = pathOrNull(entry);
            if (path != null && path.isAbsolute()) {
                files.add(entry);
                continue;
            }
            String drive = shellPaths.windowsSpelling(entry);
            if (!drive.equals(entry)) {
                files.add(drive);
            } else if (shellPaths.rootRelative(entry)) {
                rooted.add(shellPaths.normalizeRooted(entry));
            }
        }
        return new Declared(files, rooted, globs);
    }

    /** Each unread target is its own file: one shared key would let unread edits repeat without ever reaching the limit. Returns its key. */
    private String addUnread(Map<String, String> product, ToolCall tool) {
        String unknown = messages.render(MessageRef.of("hooks.editGate.unknownPath", "tool", tool.name()));
        String key = unknown + "#" + (tool.callId() != null ? tool.callId() : UUID.randomUUID().toString());
        product.put(key, unknown);
        return key;
    }

    /** Against the hook's working directory; a relative path of a call that names none has no place to resolve against and is not a target. */
    static List<Path> resolved(String workspace, List<String> paths) {
        Set<Path> result = new LinkedHashSet<>();
        for (String given : paths) {
            if (given == null || given.isBlank()) {
                continue;
            }
            Path path = pathOrNull(given.strip());
            if (path == null) {
                // Such a name reaches no file; the empty result counts it as an unread target.
                continue;
            }
            if (path.isAbsolute()) {
                result.add(path.normalize());
            } else if (workspace != null) {
                Path base = pathOrNull(workspace);
                if (base != null) {
                    result.add(base.resolve(path).normalize());
                }
            }
        }
        return new ArrayList<>(result);
    }

    /** The JDK has no way to ask whether a text is a path of this file system other than building one. */
    private static Path pathOrNull(String text) {
        try {
            return Path.of(text);
        } catch (InvalidPathException notAPath) {
            LOG.debug("The text names no path on this file system", notAPath);
            return null;
        }
    }

    private List<Predicate<Path>> exemptions(HostWorkFacts host, Declared declared) {
        List<String> given = new ArrayList<>(List.of(temporaryDirectory));
        given.addAll(host.noteRoots());
        given.addAll(declared.files());
        List<Predicate<Path>> roots = new ArrayList<>();
        for (String root : given) {
            exemption(root, roots);
        }
        declared.globs().forEach(pattern -> globExemption(pattern, roots));
        return roots;
    }

    /**
     * Adds what a glob pattern of the user exempts: what it matches and everything below that. The pattern is matched as {@code getPathMatcher("glob:" + pattern)}
     * takes it (JDK 25 {@code FileSystem.getPathMatcher}); a pattern that is no glob exempts nothing.
     */
    static void globExemption(String pattern, List<Predicate<Path>> into) {
        try {
            into.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern)::matches);
            into.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern + "/**")::matches);
        } catch (PatternSyntaxException malformed) {
            LOG.warn("A signed exempt pattern is no glob pattern; it exempts nothing", malformed);
        }
    }

    /** Adds what exempts the directory {@code root} names, with everything under it: the directory as given and, when it exists, where it really is. */
    static void exemption(String root, List<Predicate<Path>> into) {
        if (root.contains(WILDCARD)) {
            // A path with a wildcard element is no path on every file system (a Windows name cannot hold {@code *}), so it is a glob of this file system.
            PathMatcher subtree = FileSystems.getDefault().getPathMatcher("glob:{" + globLiteral(root) + "," + globLiteral(root) + "/**}");
            into.add(subtree::matches);
            return;
        }
        Path given = pathOrNull(root);
        if (given == null) {
            return;
        }
        Path directory = given.toAbsolutePath().normalize();
        into.add(target -> target.startsWith(directory));
        if (!Files.exists(directory)) {
            return;
        }
        try {
            Path real = directory.toRealPath();
            if (!real.equals(directory)) {
                into.add(target -> target.startsWith(real));
            }
        } catch (IOException unreadable) {
            LOG.debug("The real place of an exempt directory cannot be read; its given form stands for it", unreadable);
        }
    }

    /** The text as glob syntax that matches itself: the characters the glob language reads as its own are escaped, the {@code *} elements are kept. */
    private static String globLiteral(String text) {
        StringBuilder glob = new StringBuilder(text.length() + 8);
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if ("\\?[]{},".indexOf(character) >= 0) {
                glob.append('\\');
            }
            glob.append(character);
        }
        return glob.toString();
    }
}
