// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks;

import java.util.List;
import java.util.Set;

/**
 * The boundary code that six rules do not count: b (operating system), c-getenv, c-getproperty, g-string-switch, g-string-equals and i-path-string.
 * The user's position is that none of these APIs is wrong in itself: ProcessBuilder, System.getenv, a switch over strings and string paths are normal where the
 * program meets the outside (a host's hook input, a child process, the installer, the binding of settings, a format of the build tool). What the rules
 * must catch is the same API in the code that decides (skills, workflow, continuity, context, trust). So the rules count the product's business code, and this
 * one list names the places that are not: a package (its sub-packages too), one type, or a whole module, each with the one reason it is a boundary.
 * A place that is not clearly one of these is not on the list; it stays counted and is reported for the people to decide.
 * Every entry must match a source of the product (a renamed or removed package fails the check of the list, so the list does not go stale).
 */
final class Boundaries {
    private Boundaries() {
    }

    static final String BASE = "io/github/jaeseongs95/sennard/";

    static final Set<String> NARROWED = Set.of("b-os-branching", "c-getenv", "c-getproperty", "g-string-switch", "g-string-equals", "i-path-string");
    /** The rules of {@link #NARROWED} except the operating system rule: a flag that makes a decision by the operating system stays counted in a boundary class too. */
    static final Set<String> ALL_BUT_OS = Set.of("c-getenv", "c-getproperty", "g-string-switch", "g-string-equals", "i-path-string");

    /** One boundary: the file prefix it covers (from the java folder), why, and which of the narrowed rules it is left out of. */
    record Boundary(String prefix, boolean exact, String reason, Set<String> rules) {
        boolean covers(String rule, String file) {
            return rules.contains(rule) && (exact ? file.equals(prefix) : file.startsWith(prefix));
        }

        boolean matches(String file) {
            return exact ? file.equals(prefix) : file.startsWith(prefix);
        }
    }

    private static Boundary module(String module, String reason, Set<String> rules) {
        return new Boundary(module + "/src/main/java/", false, reason, rules);
    }

    private static Boundary pkg(String module, String packageName, String reason, Set<String> rules) {
        return new Boundary(module + "/src/main/java/" + BASE + packageName.replace('.', '/') + "/", false, reason, rules);
    }

    private static Boundary type(String module, String typeName, String reason, Set<String> rules) {
        return new Boundary(module + "/src/main/java/" + BASE + typeName.replace('.', '/') + ".java", true, reason, rules);
    }

    static final List<Boundary> LIST = List.of(
            // Host hook input conversion: event, tool and field names are compared as Claude, Codex and Antigravity write them, and the host's own files and shell commands are read.
            // The agent's POSIX shell on Windows writes /d/ws/f for a drive; turning that text into a path is the job of MsysShellPaths, the one implementation of ShellPaths, in this package.
            // Path text written for another platform than the server's (a locator an agent declares, a git-style scope, a portable relative path) is read as text in ForeignPathText, also here
            // and nowhere else: java.nio.file.Path reads only this host's own syntax.
            pkg("sennard-server", "server.hook.host", "host hook input conversion, the host shell's path spelling and path text written for another platform", ALL_BUT_OS),
            // The hook client is the program a host starts for every command hook of a host that cannot post the hook itself: it reads the environment of the host that started it
            // (the time zone, a call's condition), reads the host's input by the names the host writes, and runs the installed launcher to start the server. It has no dependency on purpose.
            pkg("sennard-hook-client", "hookclient", "the command hook client: the host's environment, input and the installed launcher", ALL_BUT_OS),
            // The installer chooses the registration, the launcher and the host package folders by operating system and reads the installing process's environment.
            pkg("sennard-personal", "personal.install", "installer", NARROWED),
            type("sennard-personal", "personal.wake.AntigravityManagedConversation", "Antigravity ACP wire values", Set.of("g-string-equals")),
            // The build tool Gradle runs: reads the external formats (git output, plugin manifests, checksums) and takes its command words from the task.
            module("sennard-repo-tools", "build tool", NARROWED));

    /** Whether the rule counts this file: false for a file in a boundary that is left out of the rule. */
    static boolean counts(String rule, String file) {
        return LIST.stream().noneMatch(boundary -> boundary.covers(rule, file));
    }

    /** The violations of a rule in one file of the product: none when the file is a boundary for that rule. */
    static List<Violation> find(SourceRules.Rule rule, JavaSources.Unit unit, SqlRules.Context context) {
        return counts(rule.id(), unit.file()) ? rule.find(unit, context) : List.of();
    }
}
