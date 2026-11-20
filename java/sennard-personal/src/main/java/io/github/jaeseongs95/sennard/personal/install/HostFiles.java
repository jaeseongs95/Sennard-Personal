// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.server.hook.HookInstallation;
import io.github.jaeseongs95.sennard.server.hook.ClientHookRegistration;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.HostRegistrationFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Generates each host's hook and MCP definitions for this installation and puts the files where they run from. A plugin package ships the definitions empty
 * ({@link ClientHookRegistration#shippedFiles()}): a hook or an MCP server of a host is a command that names this PC's {@code java}, jar and server file by absolute path, which no package can know. The
 * installation writes its definition over each copy of the package the host made for itself, as a whole file ({@link HostRegistrationFile.WholeFile}) or as one member of a file that
 * holds other settings ({@link HostRegistrationFile.Member}, the rest of the file is kept). The host re-copies the shipped (empty) files when the plugin is installed or updated, so
 * {@code sennard hooks} runs again after that, and {@link #emptyCopies} names the copies that are empty now.
 *
 * <p>Every file written into a host's package is recorded with the digest of what was written (and, for a member, what it held before), and {@link #removePlaced} puts back on uninstall
 * what is still exactly that: a definition that names this installation's support files would otherwise outlive them.
 */
public final class HostFiles {
    private static final Logger LOG = LoggerFactory.getLogger(HostFiles.class);
    private static final String PLACED = "placed.json";

    private final Map<HostId, HostPackageLocation> locations;
    private final String version;
    private final String hooksCommand;
    private final JsonMapper json;

    /**
     * @param version this installation's version: only an installed plugin of the same major version gets its definition
     * @param hooksCommand the command that writes the definitions again, as the user is told to run it ({@code java -jar <jar> hooks})
     * @param json the JSON mapper of the settings context these locations come from ({@link InstalledSettings#jsonMapper()})
     */
    public HostFiles(Collection<HostPackageLocation> locations, String version, String hooksCommand, JsonMapper json) {
        this.locations = locations.stream().collect(Collectors.toMap(HostPackageLocation::host, Function.identity()));
        this.version = version;
        this.hooksCommand = hooksCommand;
        this.json = json;
    }

    /**
     * What {@link #place} did.
     *
     * @param report one line per file handled, for the command's output
     * @param unreached the folders a host loads in place that do not hold this installation's definition: they are the user's, so they are never written, and their hooks
     *     do not run this installation's hook client until the user copies the definition
     */
    public record Placement(List<String> report, List<InPlaceFolder> unreached) {
        /**
         * Ends the command that placed the files as failed when a host reads a definition this installation did not write and cannot write.
         *
         * @throws UnavailableException HOST_HOOKS_NOT_REACHED
         */
        public void requireReached() {
            if (unreached.isEmpty()) {
                return;
            }
            throw new UnavailableException(UnavailableCode.HOST_HOOKS_NOT_REACHED, SennardException.args("host", unreached.stream().map(InPlaceFolder::host).distinct().collect(Collectors.joining(", ")),
                    "targets", unreached.stream().map(folder -> folder.target().toString()).collect(Collectors.joining(", ")),
                    "definitions", unreached.stream().map(folder -> folder.definition().toString()).distinct().collect(Collectors.joining(", ")),
                    "launcher", unreached.getFirst().program()));
        }
    }

    /**
     * A definition a host reads in place.
     *
     * @param host the host's label
     * @param target the file the host reads
     * @param definition this installation's definition for that host, which is what the file should hold (for a member of a file, the member's value)
     * @param program the program the definition runs (the first word of the installation's hook program)
     */
    public record InPlaceFolder(String host, Path target, Path definition, String program) {
    }

    /** One file of a definition in the shape both kinds of {@link HostRegistrationFile} share: where it goes, which member of it (empty for the whole file) and what it holds. */
    private record Target(Path relativePath, String pointer, JsonNode content) {
        static Target of(HostRegistrationFile file) {
            return switch (file) {
                case HostRegistrationFile.WholeFile whole -> new Target(whole.relativePath(), "", whole.content());
                case HostRegistrationFile.Member member -> new Target(member.relativePath(), member.jsonPointer(), member.content());
            };
        }

        boolean whole() {
            return pointer.isEmpty();
        }

        /** The name of the member: a pointer names a member of the file's top-level object, the one shape a host's settings file is changed in. */
        String member() {
            if (pointer.length() < 2 || pointer.charAt(0) != '/' || pointer.indexOf('/', 1) >= 0 || pointer.indexOf('~') >= 0) {
                throw new IllegalArgumentException("A member of a host file is a member of its top-level object, as /name: " + pointer);
            }
            return pointer.substring(1);
        }
    }

    /** What {@link #place} recorded for one file it wrote into a host's package. */
    private record Placed(String target, String pointer, String sha256, JsonNode previous) {
    }

    /**
     * @param installation the programs this installation's hook definitions and MCP registrations run ({@link HookClientCommand})
     * @return what was placed and which in-place folders do not hold this installation's definition
     */
    public Placement place(Collection<? extends ClientHookRegistration> adapters, InstallEnvironment environment, HookInstallation installation, Path generatedRoot) {
        List<String> report = new ArrayList<>();
        List<InPlaceFolder> unreached = new ArrayList<>();
        try {
            List<Placed> placed = readPlaced(generatedRoot);
            for (ClientHookRegistration adapter : adapters) {
                HostPackageLocation location = locations.get(adapter.host());
                boolean placedSomewhere = false;
                for (HostRegistrationFile registration : adapter.registrationFiles(installation)) {
                    Target file = Target.of(registration);
                    Path generated = generatedRoot.resolve(adapter.host().label()).resolve(generatedName(file));
                    write(generated, file.content().toPrettyString());
                    if (location == null) {
                        continue;
                    }
                    for (HostPackageLocation.InstalledPackage installed : location.packages(environment)) {
                        Path target = installed.root().resolve(file.relativePath());
                        if (!installed.hostCopy()) {
                            // A folder the host loads in place is never written, whatever version the host recorded for it (for a plugin of a local marketplace the record
                            // may hold no version this installation can compare). When it does not hold this installation's definition the command is told so by its end: the plugin
                            // ships empty definitions, so its hooks and its MCP server do nothing until the user copies this one (Placement#requireReached says what to do).
                            placedSomewhere = true;
                            if (!holds(target, file)) {
                                report.add("left " + target + ": " + adapter.host().label() + " loads that folder in place, so it is not changed");
                                unreached.add(new InPlaceFolder(adapter.host().label(), target, generated, installation.hookProgram().getFirst()));
                            }
                            continue;
                        }
                        if (!sameMajor(installed.version())) {
                            report.add("left " + target + ": that plugin is version " + installed.version() + " and this installation is " + version);
                            continue;
                        }
                        placedSomewhere = true;
                        if (holds(target, file)) {
                            continue;
                        }
                        if (put(target, file, placed)) {
                            report.add("wrote " + target + (file.whole() ? "" : " (" + file.pointer() + ")"));
                        } else {
                            report.add("left " + target + ": there is no file of the host to put " + file.pointer() + " in");
                        }
                    }
                }
                if (!placedSomewhere && location != null) {
                    report.add(adapter.host().label() + " plugin is not installed yet; after installing it, run: " + hooksCommand);
                }
            }
            writePlaced(generatedRoot, placed);
        } catch (IOException cause) {
            throw new UnavailableException(UnavailableCode.HOST_FILES_FAILED, SennardException.args("cause", cause.toString()), cause);
        }
        return new Placement(report, unreached);
    }

    /**
     * The copies the host made of its plugin that still hold the shipped, empty definition: the host re-copied the package (the plugin was installed or updated after
     * {@code sennard hooks} ran), so its hooks and its MCP server do nothing until {@code sennard hooks} runs again. Nothing is written.
     *
     * @return the files, one per definition file of a host that holds the shipped form
     */
    public List<Path> emptyCopies(Collection<? extends ClientHookRegistration> adapters, InstallEnvironment environment) {
        List<Path> empty = new ArrayList<>();
        try {
            for (ClientHookRegistration adapter : adapters) {
                HostPackageLocation location = locations.get(adapter.host());
                if (location == null) {
                    continue;
                }
                for (HostRegistrationFile shipped : adapter.shippedFiles()) {
                    Target file = Target.of(shipped);
                    for (HostPackageLocation.InstalledPackage installed : location.packages(environment)) {
                        Path target = installed.root().resolve(file.relativePath());
                        if (installed.hostCopy() && holds(target, file)) {
                            empty.add(target);
                        }
                    }
                }
            }
        } catch (IOException cause) {
            throw new UnavailableException(UnavailableCode.HOST_FILES_FAILED, SennardException.args("cause", cause.toString()), cause);
        }
        return List.copyOf(empty);
    }

    /**
     * Puts back each file {@link #place} wrote into a host's package that still holds what was written: a whole file is deleted, a member is set to what it held before (or removed when
     * the file had none); one that changed since, such as a copy the host replaced with the shipped definition, is kept and named.
     *
     * @return one line per recorded file that still exists
     */
    public List<String> removePlaced(Path generatedRoot) {
        List<String> report = new ArrayList<>();
        List<Placed> placed;
        try {
            placed = readPlaced(generatedRoot);
        } catch (IOException cause) {
            return List.of("kept the definitions written into host plugins: " + cause);
        }
        for (Placed entry : placed) {
            Path file = Path.of(entry.target());
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                if (entry.pointer().isEmpty()) {
                    if (Digests.sha256Hex(Files.readString(file, StandardCharsets.UTF_8)).equals(entry.sha256())) {
                        Files.delete(file);
                        report.add("removed " + file);
                    } else {
                        report.add("kept " + file + ": it changed after Sennard wrote it");
                    }
                } else if (restoreMember(file, entry)) {
                    report.add("restored " + entry.pointer() + " in " + file);
                } else {
                    report.add("kept " + file + ": " + entry.pointer() + " changed after Sennard wrote it");
                }
            } catch (IOException | JacksonException cause) {
                report.add("kept " + file + ": " + cause);
            }
        }
        return report;
    }

    private boolean sameMajor(String installed) {
        return !installed.isEmpty() && major(installed).equals(major(version));
    }

    private static String major(String version) {
        int dot = version.indexOf('.');
        return dot < 0 ? version : version.substring(0, dot);
    }

    /** The file a definition is generated to for the user to copy: a whole file under its own name, a member's value in a file named after the file and the member. */
    private static Path generatedName(Target file) {
        if (file.whole()) {
            return file.relativePath();
        }
        Path name = file.relativePath().getFileName();
        Path folder = file.relativePath().getParent();
        String generated = name + "." + file.member() + ".json";
        return folder == null ? Path.of(generated) : folder.resolve(generated);
    }

    /** Whether the file already holds the definition: the whole file's JSON, or the member's value. */
    private boolean holds(Path target, Target file) throws IOException {
        Optional<JsonNode> tree = readJson(target);
        if (tree.isEmpty()) {
            return false;
        }
        return file.whole() ? tree.get().equals(file.content()) : tree.get().path(file.member()).equals(file.content());
    }

    /** The file's JSON; empty for a file that is missing or is not JSON. */
    private Optional<JsonNode> readJson(Path target) throws IOException {
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readTree(Files.readString(target, StandardCharsets.UTF_8)));
        } catch (JacksonException notJson) {
            // The file is one a host or the user may have edited: text that is not JSON is not the definition (a whole file is then written again), so it is a value, not a failure.
            LOG.debug("The host file is not JSON: {}", target.getFileName(), notJson);
            return Optional.empty();
        }
    }

    /** @return false when a member cannot be put because the host has no file there (a member is one of a file the host owns; a whole file is made when it is missing) */
    private boolean put(Path target, Target file, List<Placed> placed) throws IOException {
        if (file.whole()) {
            String definition = file.content().toPrettyString();
            write(target, definition);
            remember(placed, new Placed(target.toString(), "", Digests.sha256Hex(definition), null));
            return true;
        }
        Optional<JsonNode> tree = readJson(target);
        if (tree.isEmpty() || !(tree.get() instanceof ObjectNode object)) {
            return false;
        }
        JsonNode before = object.path(file.member());
        Optional<Placed> earlier = placed.stream().filter(entry -> entry.target().equals(target.toString()) && entry.pointer().equals(file.pointer())).findFirst();
        // A member this installation wrote earlier (an upgrade writes it again) keeps what the host's file held before the first time, which is what an uninstall puts back.
        JsonNode previous = earlier.isPresent() && digest(before).equals(earlier.get().sha256()) ? earlier.get().previous() : (before.isMissingNode() ? null : before);
        object.set(file.member(), file.content());
        write(target, object.toPrettyString());
        remember(placed, new Placed(target.toString(), file.pointer(), digest(file.content()), previous));
        return true;
    }

    private boolean restoreMember(Path file, Placed entry) throws IOException {
        Optional<JsonNode> tree = readJson(file);
        if (tree.isEmpty() || !(tree.get() instanceof ObjectNode object)) {
            return false;
        }
        String member = new Target(Path.of(entry.target()), entry.pointer(), null).member();
        if (!digest(object.path(member)).equals(entry.sha256())) {
            return false;
        }
        if (entry.previous() == null) {
            object.remove(member);
        } else {
            object.set(member, entry.previous());
        }
        write(file, object.toPrettyString());
        return true;
    }

    private String digest(JsonNode node) {
        return Digests.sha256Hex(json.writeValueAsString(node));
    }

    private static void remember(List<Placed> placed, Placed entry) {
        placed.removeIf(existing -> existing.target().equals(entry.target()) && existing.pointer().equals(entry.pointer()));
        placed.add(entry);
    }

    private List<Placed> readPlaced(Path generatedRoot) throws IOException {
        Path file = generatedRoot.resolve(PLACED);
        List<Placed> placed = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return placed;
        }
        try {
            for (JsonNode entry : json.readTree(Files.readString(file, StandardCharsets.UTF_8))) {
                JsonNode previous = entry.path("previous");
                placed.add(new Placed(entry.path("target").asString(), entry.path("pointer").asString(), entry.path("sha256").asString(), previous.isMissingNode() ? null : previous));
            }
        } catch (JacksonException unreadable) {
            throw new IOException("Could not read " + file + ": " + unreadable.getMessage(), unreadable);
        }
        return placed;
    }

    private void writePlaced(Path generatedRoot, List<Placed> placed) throws IOException {
        ArrayNode entries = json.createArrayNode();
        for (Placed entry : placed.stream().sorted(Comparator.comparing(Placed::target).thenComparing(Placed::pointer)).toList()) {
            ObjectNode node = entries.addObject();
            node.put("target", entry.target());
            node.put("pointer", entry.pointer());
            node.put("sha256", entry.sha256());
            if (entry.previous() != null) {
                node.set("previous", entry.previous());
            }
        }
        write(generatedRoot.resolve(PLACED), entries.toPrettyString());
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
