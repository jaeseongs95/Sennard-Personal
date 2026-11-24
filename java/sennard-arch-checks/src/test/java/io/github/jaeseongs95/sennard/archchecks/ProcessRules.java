// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;

/**
 * Rule q: starting or reaching another process is done in a few named places and nowhere else. A process is the one resource that outlives the code that started it,
 * so every place that starts one or holds a handle to one has to answer for its end (a deadline, a kill of the tree, a bound on what it writes); the places that do are
 * listed here with what they use it for, and a new one is added to this list in the same change that adds it, where a reviewer sees it. The compiled classes of the product
 * are read with ArchUnit, so a use is found wherever it is written (a lambda, a helper, a nested class).
 *
 * <p>Counted: a dependency of a class on {@code java.lang.ProcessBuilder} or {@code java.lang.ProcessHandle} (or a type nested in them) and a call of
 * {@code Runtime.exec}. {@code Process} alone is not counted: a class can only get one from {@code ProcessBuilder}, {@code Runtime.exec} or a handle.
 */
final class ProcessRules {
    private ProcessRules() {
    }

    /** The packages whose classes may use the process API, each with the one reason. */
    static final List<String> ALLOWED_PACKAGES = List.of(
            // HostWatch: whether the host process of a session still lives, and its exit as the signal that ends the session (ProcessHandle.of, onExit).
            "io.github.jaeseongs95.sennard.service.identity.",
            // The hook client and the MCP relay: the processes above it (the host) by ProcessHandle.parent, whether the process of the server's address file is alive and is the one that wrote it
            // (ProcessHandle.of), and the start of the server when it is not there (it runs the installed jar with the user's Java, with a deadline).
            "io.github.jaeseongs95.sennard.hookclient.",
            // The installer: the extract of the release jar and the training run it starts, the server it leaves running, the process it waits for when it stops the server.
            "io.github.jaeseongs95.sennard.personal.install.");

    /** The single classes (and their nested ones) that may use it, each with the one reason. */
    static final Set<String> ALLOWED_TYPES = Set.of(
            // The one place an OS tool is run with a deadline and an output limit.
            "io.github.jaeseongs95.sennard.protocol.ToolProcess",
            // The programs of executions (the port ProcessRunner of sennard-server): started here (ProcessBuilder), bounded in number, output and life. The watch of one execution
            // (LocalExecution), the pipes of its output (ProcessOutput) and the finder of the processes a server before this one left running (LocalOrphanProcesses) hold no handle of the
            // process API and are not on the list: they ask ProcessTree.
            "io.github.jaeseongs95.sennard.personal.LocalProcessRunner",
            // One process and the tree beneath it: found by its id (ProcessHandle.of) and listed (descendants), and the one place that ends such a tree, for the runner and for the finder
            // of the processes a server before this one left running.
            "io.github.jaeseongs95.sennard.personal.ProcessTree");

    private static final Set<String> WATCHED_TYPES = Set.of("java.lang.ProcessBuilder", "java.lang.ProcessHandle");

    /** Whether the named class is one of the places on the lists (a nested class counts with the class around it). */
    static boolean allowed(String className) {
        return ALLOWED_PACKAGES.stream().anyMatch(className::startsWith)
                || ALLOWED_TYPES.stream().anyMatch(type -> className.equals(type) || className.startsWith(type + "$"));
    }

    /** The violations as {@code Class:line what}; empty when every use of the process API is in an allowed place. */
    static List<String> violations(JavaClasses classes) {
        List<String> found = new ArrayList<>();
        for (JavaClass origin : classes) {
            if (allowed(origin.getName())) {
                continue;
            }
            for (JavaMethodCall call : origin.getMethodCallsFromSelf()) {
                if ("java.lang.Runtime".equals(call.getTargetOwner().getFullName()) && "exec".equals(call.getName())) {
                    found.add(origin.getName() + ":" + call.getLineNumber() + " calls Runtime.exec outside the places that may start a process");
                }
            }
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                String target = dependency.getTargetClass().getFullName();
                if (WATCHED_TYPES.stream().anyMatch(type -> target.equals(type) || target.startsWith(type + "$"))) {
                    found.add(origin.getName() + ":" + dependency.getSourceCodeLocation().getLineNumber() + " uses " + target + " outside the places that may start or reach a process");
                }
            }
        }
        return found.stream().distinct().sorted().toList();
    }
}
