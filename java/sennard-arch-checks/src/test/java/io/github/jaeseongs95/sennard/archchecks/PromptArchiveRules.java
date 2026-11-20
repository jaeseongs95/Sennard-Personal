// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;

/**
 * Rule r: the user's words are written from the hook path and from nowhere else. The archive of prompts has one
 * class that writes a prompt (the writer), one repository that inserts one, and one hook class that calls the writer; the MCP tools may read the archive and record a recap but
 * must not reach the writer, so that the agent can never add or change a word the user said. The database holds the same line with its grants (the service role has SELECT and
 * INSERT on the prompt table and nothing more); this rule holds the code to it before the database has to.
 *
 * <p>Five things are found, from the compiled classes (ArchUnit), wherever the use is written (a lambda, a helper, a nested class):
 * <ol>
 * <li>a class of the tool packages that defines MCP tools (one that depends on the tool entrance, or has a method with the {@code @McpTool} annotation) from which the writer, the
 * insert repository or the hook class that writes can be reached, directly or through any chain of classes ({@code getTransitiveDependenciesFromSelf}), and every class of such a
 * chain that hands events to the hook domains: a service the tool calls that is given the hook class, or the bundle of hook domains, and makes up a prompt event of its own is as
 * much a way to the writer as the tool's own use;</li>
 * <li>a class other than the writer that depends on the insert repository;</li>
 * <li>a class other than the writer and the hook classes on the list that depends on the writer;</li>
 * <li>a class other than the dispatchers (the hook processing) that depends on a hook class that writes;</li>
 * <li>a class other than the dispatchers that calls the hook method of a hook domain ({@code HookDomain.onEvent}), on the domain interface or on any class that implements it.</li>
 * </ol>
 * A dependency is any use of the type by the class: a field, a parameter, a call, a construction, a type argument. A class nested in another counts with the class around it. What
 * the rule cannot see is a link that is no dependency of the compiled classes (a bean looked up by name or by reflection, or an implementation chosen by the container behind an
 * interface the tool reaches); the database's grants hold that line.
 */
final class PromptArchiveRules {
    private PromptArchiveRules() {
    }

    /**
     * What the rule is about, by class name.
     *
     * @param writer the class that writes a prompt
     * @param insert the repository that inserts one
     * @param writers the classes (the hook path) that may call the writer
     * @param hookDomain the interface of the hook domains; the writers are among its implementations
     * @param hookMethod the method of the hook domain that handles one hook event
     * @param dispatchers the classes that may hand a hook event to the hook domains (the hook processing itself)
     * @param toolPackage the package prefix of the classes that may define MCP tools
     * @param entrance the class a tool definition goes through
     * @param toolAnnotation the annotation of an annotated MCP tool
     */
    record Names(String writer, String insert, Set<String> writers, String hookDomain, String hookMethod, Set<String> dispatchers, String toolPackage, String entrance, String toolAnnotation) {
        /** The product's classes. */
        static final Names PRODUCT = new Names("io.github.jaeseongs95.sennard.app.prompt.PromptArchiveWriter", "io.github.jaeseongs95.sennard.app.prompt.UserPromptWrites",
                Set.of("io.github.jaeseongs95.sennard.server.prompt.PromptCollector"), "io.github.jaeseongs95.sennard.server.session.HookDomain", "onEvent",
                Set.of("io.github.jaeseongs95.sennard.server.session.SessionEventHandler"), "io.github.jaeseongs95.sennard.server.", "io.github.jaeseongs95.sennard.server.entrance.ToolEntrance",
                "org.springframework.ai.mcp.annotation.McpTool");
    }

    /** Whether the class is one of the tool definitions: it goes through the tool entrance or has an annotated MCP tool method. */
    static boolean definesTools(JavaClass type, Names names) {
        return type.getDirectDependenciesFromSelf().stream().anyMatch(dependency -> same(dependency.getTargetClass().getName(), names.entrance()))
                || type.getMethods().stream().anyMatch(method -> method.isAnnotatedWith(names.toolAnnotation()));
    }

    /** The lines of the class where it calls (or refers to by a method reference) the hook method of a hook domain, whatever domain type the call is written against. */
    static List<Integer> domainCalls(JavaClass type, Names names) {
        List<Integer> lines = new ArrayList<>();
        for (JavaAccess<?> access : Stream.concat(type.getMethodCallsFromSelf().stream(), type.getMethodReferencesFromSelf().stream()).toList()) {
            JavaClass owner = access.getTargetOwner();
            if (names.hookMethod().equals(access.getTarget().getName()) && owner.isAssignableTo(names.hookDomain())) {
                lines.add(access.getLineNumber());
            }
        }
        return lines.stream().distinct().sorted().toList();
    }

    /** The classes of the tool packages that define tools. */
    static List<String> toolClasses(JavaClasses classes, Names names) {
        return classes.stream().filter(type -> type.getName().startsWith(names.toolPackage()) && definesTools(type, names)).map(JavaClass::getName).sorted().toList();
    }

    /** The violations as {@code Class:line what}; empty when the user's words are written only along the hook path. */
    static List<String> violations(JavaClasses classes, Names names) {
        List<String> found = new ArrayList<>(reachedFromTools(classes, names));
        for (JavaClass origin : classes) {
            String name = origin.getName();
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                String target = dependency.getTargetClass().getName();
                String at = name + ":" + dependency.getSourceCodeLocation().getLineNumber();
                if (same(target, names.insert()) && !same(name, names.writer())) {
                    found.add(at + " uses " + target + " but only " + names.writer() + " may insert a prompt");
                }
                if (same(target, names.writer()) && !same(name, names.writer()) && !isAmong(name, names.writers())) {
                    found.add(at + " uses " + target + " outside the hook classes that may write a prompt " + names.writers());
                }
                if (isAmong(target, names.writers()) && !same(name, target) && !isAmong(name, names.dispatchers())) {
                    found.add(at + " uses " + target + " outside the dispatchers " + names.dispatchers() + ": only the hook processing hands an event to a hook class that writes a prompt");
                }
            }
            if (!isAmong(name, names.dispatchers())) {
                for (int line : domainCalls(origin, names)) {
                    found.add(name + ":" + line + " calls a hook domain's " + names.hookMethod() + " but only the dispatchers " + names.dispatchers() + " may hand events to the hook domains");
                }
            }
        }
        return found.stream().distinct().sorted().toList();
    }

    /**
     * What each tool definition reaches: every class that the tool, or a class nested in it, depends on directly or through any chain of classes. In that set, a use of the writer, the
     * insert repository or a hook class that writes is a violation, and so is a class that hands events to the hook domains (it can make up the event of a prompt). Each line starts
     * with the class where the use is written and names the tool class that reaches it.
     */
    private static List<String> reachedFromTools(JavaClasses classes, Names names) {
        List<String> found = new ArrayList<>();
        for (JavaClass tool : classes) {
            if (!tool.getName().startsWith(names.toolPackage()) || !definesTools(tool, names)) {
                continue;
            }
            Set<JavaClass> own = classes.stream().filter(type -> same(type.getName(), tool.getName())).collect(Collectors.toSet());
            Set<Dependency> reach = reach(own);
            Set<JavaClass> reached = new LinkedHashSet<>(own);
            for (Dependency dependency : reach) {
                reached.add(dependency.getTargetClass());
            }
            for (Dependency dependency : reach) {
                String target = dependency.getTargetClass().getName();
                if (!writesWords(target, names)) {
                    continue;
                }
                JavaClass origin = dependency.getOriginClass();
                String at = origin.getName() + ":" + dependency.getSourceCodeLocation().getLineNumber();
                found.add(own.contains(origin) ? at + " an MCP tool class depends on " + target + ", which writes the user's words"
                        : at + " depends on " + target + ", which writes the user's words, and the MCP tool class " + tool.getName() + " reaches " + origin.getName());
            }
            for (JavaClass type : reached) {
                for (int line : domainCalls(type, names)) {
                    String at = type.getName() + ":" + line;
                    found.add(own.contains(type) ? at + " an MCP tool class hands events to the hook domains (calls " + names.hookMethod() + "), which can write a prompt the user never said"
                            : at + " hands events to the hook domains (calls " + names.hookMethod() + "), which can write a prompt the user never said, and the MCP tool class " + tool.getName()
                                    + " reaches " + type.getName());
                }
            }
        }
        return found;
    }

    /** The dependencies of the classes, direct and through any chain of classes (the standard transitive closure of ArchUnit). */
    private static Set<Dependency> reach(Set<JavaClass> from) {
        Set<Dependency> reach = new LinkedHashSet<>();
        for (JavaClass member : from) {
            reach.addAll(member.getDirectDependenciesFromSelf());
            reach.addAll(member.getTransitiveDependenciesFromSelf());
        }
        return reach;
    }

    /** The names of the classes that a class (with the classes nested in it) depends on directly or through any chain of classes. */
    static Set<String> reachedBy(JavaClasses classes, String type) {
        Set<JavaClass> own = classes.stream().filter(candidate -> same(candidate.getName(), type)).collect(Collectors.toSet());
        return reach(own).stream().map(dependency -> dependency.getTargetClass().getName()).collect(Collectors.toCollection(TreeSet::new));
    }

    /** The writer, the insert repository or a hook class that writes (or a class nested in one of them). */
    private static boolean writesWords(String name, Names names) {
        return same(name, names.writer()) || same(name, names.insert()) || isAmong(name, names.writers());
    }

    private static boolean isAmong(String name, Set<String> types) {
        return types.stream().anyMatch(type -> same(name, type));
    }

    /** The class itself, or a class nested in it. */
    private static boolean same(String name, String type) {
        return name.equals(type) || name.startsWith(type + "$");
    }
}
