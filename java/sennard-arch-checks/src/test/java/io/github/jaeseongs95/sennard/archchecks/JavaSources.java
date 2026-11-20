// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;

/**
 * Java sources read as syntax trees with the JDK's own compiler API (jdk.compiler, a supported API: no dependency and no access to JDK
 * internals). Only parsing runs, so a file is read without its classpath; the rules that need a type work from the names written in the source.
 */
final class JavaSources {
    private JavaSources() {
    }

    /** A parsed file; {@code file} is the name reported in a violation. */
    record Unit(String file, CompilationUnitTree tree, SourcePositions positions, List<String> lines) {
        int line(Tree node) {
            LineMap map = tree.getLineMap();
            return (int) map.getLineNumber(positions.getStartPosition(tree, node));
        }

        String text(int line) {
            return line >= 1 && line <= lines.size() ? lines.get(line - 1).strip() : "";
        }

        Violation violation(String rule, Tree node) {
            int line = line(node);
            return new Violation(rule, file, line, text(line));
        }
    }

    private static final class Source extends SimpleJavaFileObject {
        private final String code;

        Source(int index, String code) {
            super(URI.create("mem:///source" + index + ".java"), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    /** Parses sources given as name and text. */
    static List<Unit> parse(Map<String, String> sources) {
        List<Source> objects = new ArrayList<>();
        Map<URI, String> names = new HashMap<>();
        Map<URI, String> texts = new HashMap<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            Source source = new Source(objects.size(), entry.getValue());
            objects.add(source);
            names.put(source.toUri(), entry.getKey());
            texts.put(source.toUri(), entry.getValue());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("the tests run on a JRE without jdk.compiler; the source checks need the JDK");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostics, List.of("-proc:none"), null, objects);
        List<Unit> units = new ArrayList<>();
        try {
            Trees trees = Trees.instance(task);
            for (CompilationUnitTree tree : task.parse()) {
                URI uri = tree.getSourceFile().toUri();
                units.add(new Unit(names.get(uri), tree, trees.getSourcePositions(), texts.get(uri).lines().toList()));
            }
        } catch (IOException cause) {
            throw new IllegalStateException("cannot parse the sources", cause);
        }
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                String where = diagnostic.getSource() == null ? "?" : names.get(diagnostic.getSource().toUri());
                throw new IllegalStateException("syntax error in " + where + ":" + diagnostic.getLineNumber() + " " + diagnostic.getMessage(null));
            }
        }
        return units;
    }

    /** The path from {@code base} with the names joined by a slash, the same on every operating system. */
    static String relative(Path base, Path file) {
        List<String> names = new ArrayList<>();
        for (Path name : base.relativize(file)) {
            names.add(name.toString());
        }
        return String.join("/", names);
    }

    /** Parses the {@code .java} files under the given roots; a file is named by its path from {@code base}. */
    static List<Unit> parseFiles(Path base, List<Path> files) {
        Map<String, String> sources = new java.util.TreeMap<>();
        for (Path file : files) {
            try {
                sources.put(relative(base, file), Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException cause) {
                throw new IllegalStateException("cannot read " + file, cause);
            }
        }
        return parse(sources);
    }
}
