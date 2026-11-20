// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.util.TreeScanner;

/**
 * Rule q, a host's name is written in one place: the product never writes a host's name (the label its hooks post under, the name its sessions are stored under)
 * outside the one file that defines the host. A host is defined where a {@code new HostId("...", "...")} is written (an adapter package's {@code <Host>Host} class);
 * every string literal in the product's main sources that equals a name given there, in any other file, is a violation: common code compares and passes the
 * {@code HostId}, and the adapters read their names from the definition, so a name has one place and a new host is an adapter and its definition and nothing else.
 *
 * <p>A literal is compared whole, as the product writes it ({@code "claude"} is a name, {@code ".claude"} and {@code "claude-plugin"} are not). The tests are not read:
 * they write the posted labels as the outside does (a URL, a header, a fixture).
 */
final class HostRules {
    static final String ID = "q-host-name-literal";
    private static final String HOST_ID = "HostId";

    private HostRules() {
    }

    /** The names defined by the {@code new HostId(...)} of the sources, each with the file that defines it. */
    static Map<String, String> definedNames(List<JavaSources.Unit> sources) {
        Map<String, String> definedIn = new HashMap<>();
        for (JavaSources.Unit unit : sources) {
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitNewClass(NewClassTree node, Void unused) {
                    String type = SourceRules.dotted(node.getIdentifier());
                    if (type != null && (HOST_ID.equals(type) || type.endsWith("." + HOST_ID))) {
                        node.getArguments().forEach(argument -> {
                            String name = SourceRules.stringValue(argument);
                            if (name != null) {
                                definedIn.putIfAbsent(name, unit.file());
                            }
                        });
                    }
                    return super.visitNewClass(node, unused);
                }
            }.scan(unit.tree(), null);
        }
        return definedIn;
    }

    /** Every literal of the sources that is a defined host name, in a file other than the one that defines it. */
    static List<Violation> find(List<JavaSources.Unit> sources) {
        Map<String, String> definedIn = definedNames(sources);
        List<Violation> found = new ArrayList<>();
        for (JavaSources.Unit unit : sources) {
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitLiteral(LiteralTree node, Void unused) {
                    String value = SourceRules.stringValue(node);
                    if (value != null && definedIn.containsKey(value) && !unit.file().equals(definedIn.get(value))) {
                        found.add(unit.violation(ID, node));
                    }
                    return super.visitLiteral(node, unused);
                }
            }.scan(unit.tree(), null);
        }
        return found;
    }
}
