// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

/**
 * Rule a: two packages of the product that depend on each other. The compiled classes of every module are read with ArchUnit; a package depends
 * on another when one of its classes refers to one of the other's (a field, a parameter, a call, an annotation, a supertype). A package and its
 * sub-package are two packages here.
 */
final class PackageCycles {
    private PackageCycles() {
    }

    static final String ROOT = "io.github.jaeseongs95.sennard";

    /** One reference from a package to another: where the first one is. */
    private record Edge(String file, int line, String from, String to) {
        String place() {
            return file + ":" + line;
        }
    }

    static List<Violation> directCycles(String rule, List<Path> classDirectories) {
        JavaClasses classes = new ClassFileImporter().importPaths(classDirectories);
        Map<String, Edge> edges = new TreeMap<>();
        for (JavaClass origin : classes) {
            String from = origin.getPackageName();
            if (!from.startsWith(ROOT)) {
                continue;
            }
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                String to = dependency.getTargetClass().getPackageName();
                if (!to.startsWith(ROOT) || to.equals(from)) {
                    continue;
                }
                edges.putIfAbsent(from + " -> " + to, new Edge(sourceFile(origin, dependency), dependency.getSourceCodeLocation().getLineNumber(), from, to));
            }
        }
        List<Violation> cycles = new ArrayList<>();
        for (Edge edge : edges.values()) {
            Edge back = edges.get(edge.to() + " -> " + edge.from());
            if (back != null && edge.from().compareTo(edge.to()) < 0) {
                cycles.add(new Violation(rule, edge.file(), edge.line(), edge.from() + " <-> " + edge.to() + "   [" + edge.from() + " -> " + edge.to() + " at " + edge.place()
                        + ", " + edge.to() + " -> " + edge.from() + " at " + back.place() + "]"));
            }
        }
        return cycles;
    }

    /** The module folder and the source file of the class where the reference is written. */
    private static String sourceFile(JavaClass origin, Dependency dependency) {
        String module = origin.getSource().map(source -> {
            String uri = source.getUri().toString();
            int build = uri.indexOf("/build/classes");
            return build < 0 ? "" : uri.substring(uri.lastIndexOf('/', build - 1) + 1, build);
        }).orElse("");
        List<String> parts = new ArrayList<>();
        if (!module.isEmpty()) {
            parts.add(module);
            parts.add("src/main/java");
        }
        parts.add(origin.getPackageName().replace('.', '/'));
        parts.add(dependency.getSourceCodeLocation().getSourceFileName());
        return String.join("/", parts);
    }
}
