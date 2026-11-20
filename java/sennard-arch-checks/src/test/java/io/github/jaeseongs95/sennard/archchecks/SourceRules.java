// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;

import javax.lang.model.element.Modifier;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ConstantCaseLabelTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;

/**
 * The rules that read Java source (docs/sennard60-architecture-checks.md). Each rule is a function from a parsed file to the places where it is broken;
 * what it recognises is spelled out where it is written, and what it cannot see (a name resolved only by the compiler) is listed in the document.
 */
final class SourceRules {
    private SourceRules() {
    }

    /** A rule: its id, what it counts, and how it finds the places in one file. */
    record Rule(String id, String meaning, BiFunction<JavaSources.Unit, SqlRules.Context, List<Violation>> finder) {
        /** A rule that needs nothing but the file. */
        Rule(String id, String meaning, Function<JavaSources.Unit, List<Violation>> plain) {
            this(id, meaning, (unit, context) -> plain.apply(unit));
        }

        List<Violation> find(JavaSources.Unit unit, SqlRules.Context context) {
            return finder.apply(unit, context);
        }
    }

    static final Rule OS_BRANCHING = new Rule("b-os-branching",
            "a decision by operating system: the \"os.name\" property name, File.separator, File.separatorChar, FileSystem getSeparator()", SourceRules::osBranching);
    static final Rule VALUE_ANNOTATION = new Rule("c-value-annotation", "@Value (settings belong in @ConfigurationProperties records)", unit -> scan(unit, "c-value-annotation", new ValueAnnotation()));
    static final Rule GETENV = new Rule("c-getenv", "System.getenv", unit -> properties(unit, "c-getenv", Reads.ENVIRONMENT));
    static final Rule GETPROPERTY = new Rule("c-getproperty", "System.getProperty and the Boolean/Integer/Long property readers, for a key that is not a JVM standard property", unit -> properties(unit, "c-getproperty", Reads.OTHER_PROPERTY));
    static final Rule GETPROPERTY_JVM = new Rule("c-getproperty-jvm", "the same readers for a JVM standard property (java.*, os.*, user.*, file.*, path.*, line.*, ...)", unit -> properties(unit, "c-getproperty-jvm", Reads.JVM_PROPERTY));
    static final Rule STATEFUL_STATIC = new Rule("d-stateful-static",
            "a static field that holds state: not final, or final but a lock, random source, mutable collection or other mutable object", SourceRules::statefulStatic);
    static final Rule CONSOLE_OUTPUT = new Rule("e-console-output", "System.out and System.err", unit -> scan(unit, "e-console-output", new ConsoleOutput()));
    static final Rule PRINT_STACK_TRACE = new Rule("e-print-stack-trace", "printStackTrace()", unit -> scan(unit, "e-print-stack-trace", new PrintStackTrace()));
    static final Rule SWALLOWED_CATCH = new Rule("f-swallowed-catch", "a catch block that neither logs, nor throws, nor passes the exception on", unit -> scan(unit, "f-swallowed-catch", new SwallowedCatch()));
    static final Rule STRING_SWITCH = new Rule("g-string-switch", "a switch with string constants as case labels", unit -> scan(unit, "g-string-switch", new StringSwitch()));
    static final Rule STRING_EQUALS = new Rule("g-string-equals", "equals, equalsIgnoreCase or Objects.equals against a word-like string constant", SourceRules::stringEquals);
    static final Rule PATH_STRING = new Rule("i-path-string", "a path handled as a string: separator replaced, drive pattern, startsWith(parent + \"/\"), split or lastIndexOf on a separator, a separator joined by +", unit -> scan(unit, "i-path-string", new PathString()));

    /** The folder of the domain module's sources, as a file of the repository's java folder is named in a violation. */
    static final String DOMAIN_SOURCES = "sennard-domain/src/main/java/";
    private static final List<String> DOMAIN_FORBIDDEN = List.of("org.springframework", "java.sql", "javax.sql", "tools.jackson.databind");

    static final Rule DOMAIN_IMPORTS = new Rule("o-domain-imports",
            "a source of sennard-domain that imports, or names by its full name, org.springframework.*, java.sql.*, javax.sql.* or tools.jackson.databind.* (the domain is plain Java: no framework, no database, no JSON tree)",
            unit -> unit.file().startsWith(DOMAIN_SOURCES) ? scan(unit, "o-domain-imports", new DomainImports()) : List.of());

    static final Rule OPTIONAL_NOT_RETURN = new Rule("p-optional-not-return",
            "an Optional (or OptionalInt, OptionalLong, OptionalDouble) as a field, a record component, a method parameter or the element of a collection of those (Optional is for a method's return type: \"no result\")",
            unit -> scan(unit, "p-optional-not-return", new OptionalNotReturn()));
    static final Rule OR_ELSE_NULL = new Rule("p-or-else-null", "orElse(null): a null brought back out of an Optional (a method that may answer nothing returns the Optional on, or maps it)",
            unit -> scan(unit, "p-or-else-null", new OrElseNull()));

    static final List<Rule> ALL = List.of(OS_BRANCHING, VALUE_ANNOTATION, GETENV, GETPROPERTY, GETPROPERTY_JVM, STATEFUL_STATIC, CONSOLE_OUTPUT, PRINT_STACK_TRACE,
            SWALLOWED_CATCH, STRING_SWITCH, STRING_EQUALS, PATH_STRING, DOMAIN_IMPORTS, OPTIONAL_NOT_RETURN, OR_ELSE_NULL);

    // ------------------------------------------------------------------------------------------------------------------ names

    /** {@code a.b.c} for identifiers joined by dots, else null. */
    static String dotted(Tree tree) {
        if (tree instanceof IdentifierTree identifier) {
            return identifier.getName().toString();
        }
        if (tree instanceof MemberSelectTree select) {
            String inner = dotted(select.getExpression());
            return inner == null ? null : inner + "." + select.getIdentifier();
        }
        return null;
    }

    /** The method name of a call: the last name of its select. */
    static String methodName(MethodInvocationTree call) {
        ExpressionTree select = call.getMethodSelect();
        if (select instanceof MemberSelectTree member) {
            return member.getIdentifier().toString();
        }
        return select instanceof IdentifierTree identifier ? identifier.getName().toString() : "";
    }

    static boolean is(String dotted, String... names) {
        if (dotted == null) {
            return false;
        }
        for (String name : names) {
            if (dotted.equals(name)) {
                return true;
            }
        }
        return false;
    }

    static String stringValue(Tree tree) {
        return tree instanceof LiteralTree literal && literal.getKind() == Tree.Kind.STRING_LITERAL ? (String) literal.getValue() : null;
    }

    private static List<Violation> scan(JavaSources.Unit unit, String rule, Scanner scanner) {
        scanner.start(unit, rule);
        scanner.scan(unit.tree(), null);
        return scanner.found;
    }

    /** Collects the places of one rule while walking a file. */
    abstract static class Scanner extends TreePathScanner<Void, Void> {
        JavaSources.Unit unit;
        String rule;
        final List<Violation> found = new ArrayList<>();

        void start(JavaSources.Unit file, String id) {
            this.unit = file;
            this.rule = id;
        }

        void hit(Tree node) {
            found.add(unit.violation(rule, node));
        }
    }

    // ---------------------------------------------------------------------------------------------------- b: operating system

    private static List<Violation> osBranching(JavaSources.Unit unit) {
        return scan(unit, OS_BRANCHING.id(), new Scanner() {
            @Override
            public Void visitLiteral(LiteralTree node, Void unused) {
                if ("os.name".equals(stringValue(node))) {
                    hit(node);
                }
                return super.visitLiteral(node, unused);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                if (is(dotted(node), "File.separator", "File.separatorChar", "java.io.File.separator", "java.io.File.separatorChar")) {
                    hit(node);
                }
                return super.visitMemberSelect(node, unused);
            }

            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                if ("getSeparator".equals(methodName(node)) && node.getArguments().isEmpty()) {
                    hit(node);
                }
                return super.visitMethodInvocation(node, unused);
            }

            @Override
            public Void visitImport(ImportTree node, Void unused) {
                String name = dotted(node.getQualifiedIdentifier());
                if (node.isStatic() && name != null && (name.startsWith("java.io.File.separator"))) {
                    hit(node);
                }
                return super.visitImport(node, unused);
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ c: settings from outside

    private static final class ValueAnnotation extends Scanner {
        @Override
        public Void visitAnnotation(AnnotationTree node, Void unused) {
            if (is(dotted(node.getAnnotationType()), "Value", "org.springframework.beans.factory.annotation.Value")) {
                hit(node);
            }
            return super.visitAnnotation(node, unused);
        }
    }

    private enum Reads {
        ENVIRONMENT, OTHER_PROPERTY, JVM_PROPERTY
    }

    private static final List<String> JVM_PROPERTY_PREFIXES = List.of("java.", "jdk.", "os.", "user.", "file.", "path.", "line.", "native.", "stdout.", "stderr.", "sun.");
    private static final Set<String> PROPERTY_READERS = Set.of("System.getProperty", "java.lang.System.getProperty", "System.getProperties", "java.lang.System.getProperties",
            "Boolean.getBoolean", "Integer.getInteger", "Long.getLong");

    private static List<Violation> properties(JavaSources.Unit unit, String rule, Reads reads) {
        return scan(unit, rule, new Scanner() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                String name = dotted(node.getMethodSelect());
                if (name != null) {
                    if (reads == Reads.ENVIRONMENT && is(name, "System.getenv", "java.lang.System.getenv")) {
                        hit(node);
                    } else if (reads != Reads.ENVIRONMENT && PROPERTY_READERS.contains(name)) {
                        String key = node.getArguments().isEmpty() ? null : stringValue(node.getArguments().get(0));
                        boolean standard = key != null && JVM_PROPERTY_PREFIXES.stream().anyMatch(key::startsWith);
                        if (standard == (reads == Reads.JVM_PROPERTY)) {
                            hit(node);
                        }
                    }
                }
                return super.visitMethodInvocation(node, unused);
            }
        });
    }

    // -------------------------------------------------------------------------------------------------------------- d: static state

    /** Types whose instances change; a static final field of one of them is state, not a constant. */
    private static final Set<String> MUTABLE_TYPES = Set.of("SecureRandom", "Random", "SplittableRandom", "Lock", "ReentrantLock", "ReentrantReadWriteLock", "ReadWriteLock",
            "StampedLock", "Semaphore", "CountDownLatch", "CyclicBarrier", "AtomicInteger", "AtomicLong", "AtomicBoolean", "AtomicReference", "AtomicIntegerArray", "LongAdder",
            "ConcurrentHashMap", "ConcurrentMap", "ConcurrentLinkedQueue", "ConcurrentLinkedDeque", "CopyOnWriteArrayList", "HashMap", "LinkedHashMap", "TreeMap", "WeakHashMap",
            "IdentityHashMap", "EnumMap", "ArrayList", "LinkedList", "HashSet", "LinkedHashSet", "TreeSet", "ArrayDeque", "Deque", "Queue", "BlockingQueue", "StringBuilder",
            "StringBuffer", "MessageDigest", "Mac", "Cipher", "SimpleDateFormat", "DateFormat", "ThreadLocal", "ExecutorService", "ScheduledExecutorService", "Executor",
            "ThreadPoolExecutor", "Thread", "Process", "Matcher", "Scanner", "Cache", "LoadingCache");
    /** Collection interfaces: mutable unless the initializer is one of the factories that return an unmodifiable collection. */
    private static final Set<String> COLLECTION_TYPES = Set.of("List", "Map", "Set", "Collection", "SortedMap", "SortedSet", "NavigableMap", "NavigableSet");
    private static final Set<String> IMMUTABLE_FACTORIES = Set.of("of", "copyOf", "ofEntries", "entry", "toList", "unmodifiableList", "unmodifiableSet", "unmodifiableMap",
            "unmodifiableCollection", "unmodifiableSortedSet", "unmodifiableSortedMap", "emptyList", "emptyMap", "emptySet", "singleton", "singletonList", "singletonMap",
            "toUnmodifiableList", "toUnmodifiableSet", "toUnmodifiableMap");

    private static String typeName(Tree type) {
        if (type instanceof ParameterizedTypeTree parameterized) {
            return typeName(parameterized.getType());
        }
        if (type instanceof MemberSelectTree select) {
            return select.getIdentifier().toString();
        }
        if (type instanceof IdentifierTree identifier) {
            return identifier.getName().toString();
        }
        if (type instanceof PrimitiveTypeTree primitive) {
            return primitive.getPrimitiveTypeKind().toString().toLowerCase(java.util.Locale.ROOT);
        }
        return type instanceof com.sun.source.tree.ArrayTypeTree ? "[]" : "";
    }

    private static boolean immutableFactory(ExpressionTree init) {
        return init instanceof MethodInvocationTree call && IMMUTABLE_FACTORIES.contains(methodName(call));
    }

    private static List<Violation> statefulStatic(JavaSources.Unit unit) {
        return scan(unit, STATEFUL_STATIC.id(), new Scanner() {
            @Override
            public Void visitVariable(VariableTree node, Void unused) {
                if (getCurrentPath().getParentPath().getLeaf() instanceof ClassTree owner) {
                    boolean inInterface = owner.getKind() == Tree.Kind.INTERFACE || owner.getKind() == Tree.Kind.ANNOTATION_TYPE;
                    Set<Modifier> flags = node.getModifiers().getFlags();
                    boolean isStatic = inInterface || flags.contains(Modifier.STATIC);
                    boolean isFinal = inInterface || flags.contains(Modifier.FINAL);
                    boolean enumConstant = owner.getKind() == Tree.Kind.ENUM && node.getInitializer() instanceof NewClassTree
                            && node.getType() instanceof IdentifierTree type && type.getName().contentEquals(owner.getSimpleName());
                    if (isStatic && !enumConstant && (!isFinal || mutable(node))) {
                        hit(node);
                    }
                }
                return super.visitVariable(node, unused);
            }

            private boolean mutable(VariableTree node) {
                String type = typeName(node.getType());
                ExpressionTree init = node.getInitializer();
                if (type.equals("[]") || MUTABLE_TYPES.contains(type)) {
                    return true;
                }
                if (type.equals("Object")) {
                    return init instanceof NewClassTree; // new Object(): a lock
                }
                if (COLLECTION_TYPES.contains(type)) {
                    return init != null && !immutableFactory(init);
                }
                return init instanceof NewClassTree created && MUTABLE_TYPES.contains(typeName(created.getIdentifier()));
            }
        });
    }

    // -------------------------------------------------------------------------------------------------------------- e: console

    private static final class ConsoleOutput extends Scanner {
        @Override
        public Void visitMemberSelect(MemberSelectTree node, Void unused) {
            if (is(dotted(node), "System.out", "System.err", "java.lang.System.out", "java.lang.System.err")) {
                hit(node);
            }
            return super.visitMemberSelect(node, unused);
        }

        @Override
        public Void visitImport(ImportTree node, Void unused) {
            String name = dotted(node.getQualifiedIdentifier());
            if (node.isStatic() && is(name, "java.lang.System.out", "java.lang.System.err")) {
                hit(node);
            }
            return super.visitImport(node, unused);
        }
    }

    /**
     * o, the domain's imports: in a file of sennard-domain, an import (also a static one, also a wildcard) of a forbidden package, or a use of one by its full name
     * in the code. A file of another module is not looked at. What it cannot see: a type of a forbidden package that arrives without being named (a call whose
     * result type is one); the module's build script has no dependency on them anyway, so such a type could not be named in the first place.
     */
    private static final class DomainImports extends Scanner {
        private boolean forbidden(String name) {
            return name != null && DOMAIN_FORBIDDEN.stream().anyMatch(prefix -> name.equals(prefix) || name.startsWith(prefix + "."));
        }

        @Override
        public Void visitImport(ImportTree node, Void unused) {
            if (forbidden(dotted(node.getQualifiedIdentifier()))) {
                hit(node);
            }
            return null; // the imported name is the whole import: its inner selects are not separate uses
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree node, Void unused) {
            if (forbidden(dotted(node))) {
                hit(node);
                return null; // the outermost full name is the use; its package parts are not
            }
            return super.visitMemberSelect(node, unused);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------- p: Optional

    private static final Set<String> OPTIONAL_TYPES = Set.of("Optional", "OptionalInt", "OptionalLong", "OptionalDouble", "java.util.Optional", "java.util.OptionalInt", "java.util.OptionalLong",
            "java.util.OptionalDouble");
    private static final Set<String> ELEMENT_HOLDER_TYPES = Set.of("List", "Set", "Collection", "Iterable", "Map", "Queue", "Deque", "SortedSet", "SortedMap", "NavigableSet", "NavigableMap",
            "java.util.List", "java.util.Set", "java.util.Collection", "java.lang.Iterable", "java.util.Map", "java.util.Queue", "java.util.Deque");

    /** A field or record component (a record's components are fields of its class tree), or a method parameter, whose type is an Optional or a collection of them. A local variable and a lambda parameter are not looked at. */
    private static final class OptionalNotReturn extends Scanner {
        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            Tree parent = getCurrentPath().getParentPath().getLeaf();
            if ((parent instanceof ClassTree || parent instanceof MethodTree) && holdsOptional(node.getType())) {
                hit(node);
            }
            return super.visitVariable(node, unused);
        }

        private static boolean holdsOptional(Tree type) {
            if (type instanceof ParameterizedTypeTree parameterized) {
                String name = dotted(parameterized.getType());
                if (name != null && OPTIONAL_TYPES.contains(name)) {
                    return true;
                }
                return name != null && ELEMENT_HOLDER_TYPES.contains(name) && parameterized.getTypeArguments().stream().anyMatch(OptionalNotReturn::holdsOptional);
            }
            String name = dotted(type);
            return name != null && OPTIONAL_TYPES.contains(name);
        }
    }

    private static final class OrElseNull extends Scanner {
        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            if ("orElse".equals(methodName(node)) && node.getArguments().size() == 1 && node.getArguments().get(0) instanceof LiteralTree literal && literal.getKind() == Tree.Kind.NULL_LITERAL) {
                hit(node);
            }
            return super.visitMethodInvocation(node, unused);
        }
    }

    private static final class PrintStackTrace extends Scanner {
        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            if ("printStackTrace".equals(methodName(node))) {
                hit(node);
            }
            return super.visitMethodInvocation(node, unused);
        }
    }

    // --------------------------------------------------------------------------------------------------------- f: swallowed catch

    private static final Set<String> LOG_METHODS = Set.of("trace", "debug", "info", "warn", "error", "log", "severe", "warning", "fine", "finer", "finest", "atTrace", "atDebug", "atInfo", "atWarn", "atError");

    private static final class SwallowedCatch extends Scanner {
        @Override
        public Void visitCatch(CatchTree node, Void unused) {
            Handling handling = new Handling(node.getParameter().getName().toString());
            handling.scan(node.getBlock(), null);
            if (!handling.thrown && !handling.logged && !handling.interrupted && !handling.passedOn) {
                hit(node);
            }
            return super.visitCatch(node, unused);
        }
    }

    /** What a catch block does with the exception: throws, logs, restores the interrupt flag, or hands the exception object on. */
    private static final class Handling extends TreeScanner<Void, Void> {
        private final String parameter;
        boolean thrown;
        boolean logged;
        boolean interrupted;
        boolean passedOn;

        Handling(String parameter) {
            this.parameter = parameter;
        }

        @Override
        public Void visitThrow(ThrowTree node, Void unused) {
            thrown = true;
            return super.visitThrow(node, unused);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            String name = methodName(node);
            if (node.getMethodSelect() instanceof MemberSelectTree select) {
                String receiver = dotted(select.getExpression());
                if (LOG_METHODS.contains(name) && receiver != null && receiver.toLowerCase(java.util.Locale.ROOT).contains("log")) {
                    logged = true;
                }
                if (name.equals("interrupt")) {
                    interrupted = true;
                }
            }
            return super.visitMethodInvocation(node, unused);
        }

        /** {@code e.getMessage()} and {@code e.getClass()} use the exception without keeping it; a bare {@code e} keeps it. */
        @Override
        public Void visitMemberSelect(MemberSelectTree node, Void unused) {
            if (node.getExpression() instanceof IdentifierTree identifier && identifier.getName().contentEquals(parameter)) {
                return null;
            }
            return super.visitMemberSelect(node, unused);
        }

        @Override
        public Void visitIdentifier(IdentifierTree node, Void unused) {
            if (node.getName().contentEquals(parameter)) {
                passedOn = true;
            }
            return super.visitIdentifier(node, unused);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------- g: kinds by string

    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_.:\\-]+");

    private static final class StringSwitch extends Scanner {
        @Override
        public Void visitSwitch(SwitchTree node, Void unused) {
            if (hasStringLabel(node.getCases())) {
                hit(node);
            }
            return super.visitSwitch(node, unused);
        }

        @Override
        public Void visitSwitchExpression(SwitchExpressionTree node, Void unused) {
            if (hasStringLabel(node.getCases())) {
                hit(node);
            }
            return super.visitSwitchExpression(node, unused);
        }

        private static boolean hasStringLabel(List<? extends CaseTree> cases) {
            return cases.stream().flatMap(c -> c.getLabels().stream())
                    .anyMatch(label -> label instanceof ConstantCaseLabelTree constant && stringValue(constant.getConstantExpression()) != null);
        }
    }

    /** The final String variables of a file that are set to a literal: a comparison against one of them is a comparison against its literal. */
    private static Map<String, String> stringConstants(JavaSources.Unit unit) {
        Map<String, String> constants = new HashMap<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitVariable(VariableTree node, Void unused) {
                String value = node.getInitializer() == null ? null : stringValue(node.getInitializer());
                if (value != null && node.getModifiers().getFlags().contains(Modifier.FINAL)) {
                    constants.put(node.getName().toString(), value);
                }
                return super.visitVariable(node, unused);
            }
        }.scan(unit.tree(), null);
        return constants;
    }

    private static List<Violation> stringEquals(JavaSources.Unit unit) {
        Map<String, String> constants = stringConstants(unit);
        return scan(unit, STRING_EQUALS.id(), new Scanner() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                String name = methodName(node);
                boolean instanceForm = Set.of("equals", "equalsIgnoreCase", "contentEquals").contains(name) && node.getArguments().size() == 1
                        && node.getMethodSelect() instanceof MemberSelectTree;
                if (instanceForm) {
                    ExpressionTree receiver = ((MemberSelectTree) node.getMethodSelect()).getExpression();
                    if (word(receiver) || word(node.getArguments().get(0))) {
                        hit(node);
                    }
                } else if (name.equals("equals") && "Objects".equals(receiverName(node)) && node.getArguments().size() == 2
                        && (word(node.getArguments().get(0)) || word(node.getArguments().get(1)))) {
                    hit(node);
                }
                return super.visitMethodInvocation(node, unused);
            }

            private String receiverName(MethodInvocationTree node) {
                return node.getMethodSelect() instanceof MemberSelectTree select ? dotted(select.getExpression()) : null;
            }

            private boolean word(ExpressionTree tree) {
                String value = stringValue(tree);
                if (value == null && tree instanceof IdentifierTree identifier) {
                    value = constants.get(identifier.getName().toString());
                }
                return value != null && WORD.matcher(value).matches();
            }
        });
    }

    // ------------------------------------------------------------------------------------------------------------ i: path as string

    private static final Pattern BACKSLASHES = Pattern.compile("\\\\+|\\[[\\\\/]+]");
    private static final Pattern DRIVE_PATTERN = Pattern.compile("\\[A-Za-z]:|\\[a-zA-Z]:|\\\\p\\{Alpha}:");
    private static final Set<String> SEPARATORS = Set.of("/", "\\", "\\\\");

    private static final class PathString extends Scanner {
        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            String name = methodName(node);
            if (Set.of("replace", "replaceAll", "replaceFirst").contains(name) && node.getArguments().stream().anyMatch(PathString::backslash)) {
                hit(node);
            } else if (Set.of("startsWith", "endsWith").contains(name) && node.getArguments().size() == 1 && node.getArguments().get(0) instanceof BinaryTree sum
                    && sum.getKind() == Tree.Kind.PLUS && (separator(sum.getLeftOperand()) || separator(sum.getRightOperand()))) {
                hit(node);
            } else if (Set.of("lastIndexOf", "indexOf", "split").contains(name) && !node.getArguments().isEmpty() && separator(node.getArguments().get(0))) {
                hit(node);
            }
            return super.visitMethodInvocation(node, unused);
        }

        @Override
        public Void visitLiteral(LiteralTree node, Void unused) {
            String value = stringValue(node);
            if (value != null && DRIVE_PATTERN.matcher(value).find()) {
                hit(node);
            }
            return super.visitLiteral(node, unused);
        }

        @Override
        public Void visitBinary(BinaryTree node, Void unused) {
            if (node.getKind() == Tree.Kind.PLUS && (separator(node.getLeftOperand()) || separator(node.getRightOperand()))) {
                hit(node);
            } else if (node.getKind() == Tree.Kind.EQUAL_TO && driveColon(node.getLeftOperand(), node.getRightOperand())) {
                hit(node);
            }
            return super.visitBinary(node, unused);
        }

        private static boolean backslash(ExpressionTree argument) {
            if (argument instanceof LiteralTree literal) {
                if (literal.getKind() == Tree.Kind.CHAR_LITERAL) {
                    return Character.valueOf('\\').equals(literal.getValue());
                }
                String value = stringValue(literal);
                return value != null && BACKSLASHES.matcher(value).matches();
            }
            return false;
        }

        /** A string or character that is only a path separator. */
        private static boolean separator(Tree tree) {
            if (tree instanceof LiteralTree literal) {
                Object value = literal.getValue();
                return value instanceof String text ? SEPARATORS.contains(text) : value instanceof Character character && (character == '/' || character == '\\');
            }
            return false;
        }

        /** {@code x.charAt(1) == ':'}, the test for a drive letter. */
        private static boolean driveColon(ExpressionTree left, ExpressionTree right) {
            return charAtOne(left) && colon(right) || charAtOne(right) && colon(left);
        }

        private static boolean charAtOne(Tree tree) {
            return tree instanceof MethodInvocationTree call && "charAt".equals(methodName(call)) && call.getArguments().size() == 1
                    && call.getArguments().get(0) instanceof LiteralTree index && Integer.valueOf(1).equals(index.getValue());
        }

        private static boolean colon(Tree tree) {
            return tree instanceof LiteralTree literal && Character.valueOf(':').equals(literal.getValue());
        }
    }

    // ---------------------------------------------------------------------------------------------------------- m, n: SQL in the code

    /**
     * The methods that hand a parameter on to {@code .sql(...)}: their name and the position of that parameter. A call of such a helper with a
     * string passes an SQL statement, so the statement is read at the call (the stores keep their SQL at the callers of {@code rows(sql, ...)}).
     */
    static Map<String, Integer> sqlHelpers(List<JavaSources.Unit> units) {
        Map<String, Integer> helpers = new HashMap<>();
        for (JavaSources.Unit unit : units) {
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                    if ("sql".equals(methodName(node)) && node.getArguments().size() == 1 && node.getArguments().get(0) instanceof IdentifierTree argument) {
                        for (com.sun.source.util.TreePath path = getCurrentPath(); path != null; path = path.getParentPath()) {
                            if (path.getLeaf() instanceof MethodTree method) {
                                for (int i = 0; i < method.getParameters().size(); i++) {
                                    if (method.getParameters().get(i).getName().contentEquals(argument.getName())) {
                                        helpers.putIfAbsent(method.getName().toString(), i);
                                    }
                                }
                                break;
                            }
                        }
                    }
                    return super.visitMethodInvocation(node, unused);
                }
            }.scan(unit.tree(), null);
        }
        return helpers;
    }

    /**
     * The SQL statements of a file: the first argument of a {@code .sql(...)} call and the value of {@code @Query}, with the constants and
     * variables of the same file written out. A statement made of several parts keeps each part's own line.
     */
    static List<SqlRules.Statement> sqlStatements(JavaSources.Unit unit, Map<String, Integer> helpers) {
        Map<String, List<ExpressionTree>> definitions = new LinkedHashMap<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitVariable(VariableTree node, Void unused) {
                if (node.getInitializer() != null && "String".equals(typeName(node.getType()))) {
                    definitions.computeIfAbsent(node.getName().toString(), key -> new ArrayList<>()).add(node.getInitializer());
                }
                return super.visitVariable(node, unused);
            }
        }.scan(unit.tree(), null);
        List<SqlRules.Statement> statements = new ArrayList<>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                if ("sql".equals(methodName(node)) && node.getArguments().size() == 1) {
                    add(node.getArguments().get(0));
                }
                Integer index = helpers.get(methodName(node));
                if (index != null && node.getArguments().size() > index && !"sql".equals(methodName(node))) {
                    add(node.getArguments().get(index));
                }
                return super.visitMethodInvocation(node, unused);
            }

            @Override
            public Void visitAnnotation(AnnotationTree node, Void unused) {
                if (is(dotted(node.getAnnotationType()), "Query", "org.springframework.data.jdbc.repository.query.Query") && !node.getArguments().isEmpty()) {
                    ExpressionTree first = node.getArguments().get(0);
                    add(first instanceof AssignmentTree assignment ? assignment.getExpression() : first);
                }
                return super.visitAnnotation(node, unused);
            }

            private void add(ExpressionTree argument) {
                List<SqlRules.Piece> pieces = new ArrayList<>();
                collect(argument, pieces, new HashSet<>());
                if (!pieces.isEmpty()) {
                    statements.add(new SqlRules.Statement(pieces, unit.line(argument)));
                }
            }

            private void collect(Tree tree, List<SqlRules.Piece> pieces, Set<String> expanding) {
                String text = stringValue(tree);
                if (text != null) {
                    pieces.add(new SqlRules.Piece(text, unit.line(tree)));
                } else if (tree instanceof BinaryTree sum && sum.getKind() == Tree.Kind.PLUS) {
                    collect(sum.getLeftOperand(), pieces, expanding);
                    collect(sum.getRightOperand(), pieces, expanding);
                } else if (tree instanceof ParenthesizedTree parenthesized) {
                    collect(parenthesized.getExpression(), pieces, expanding);
                } else if (tree instanceof IdentifierTree identifier && expanding.add(identifier.getName().toString())) {
                    // Only the names being expanded now are guarded (a constant defined by itself would loop): a constant written twice in one statement is read twice.
                    try {
                        for (ExpressionTree definition : definitions.getOrDefault(identifier.getName().toString(), List.of())) {
                            collect(definition, pieces, expanding);
                        }
                    } finally {
                        expanding.remove(identifier.getName().toString());
                    }
                } else if (tree instanceof MethodInvocationTree call && Set.of("formatted", "stripIndent", "strip", "trim").contains(methodName(call))
                        && call.getMethodSelect() instanceof MemberSelectTree select) {
                    collect(select.getExpression(), pieces, expanding);
                }
            }
        }.scan(unit.tree(), null);
        return statements;
    }

    /** The id of the rule that keeps accounts, tenants and namespaces out of the schema, the SQL of the code and its repositories (the SQL half is {@link SqlRules.Kind#MULTI_USER_COLUMN}). */
    static final String MULTI_USER_COLUMNS = "n-no-multi-user-columns";

    /** A field of a {@code @Table} entity (a column), and the name of a derived query of a repository interface, that is a column telling users, tenants or namespaces apart. */
    private static final Pattern MULTI_USER_FIELD = Pattern.compile("accountId|ns|namespace|tenant|tenantId");
    private static final Pattern MULTI_USER_DERIVED = Pattern.compile("(?:By|And|Or)(?:Key)?(?:AccountId|Ns|Namespace|Tenant(?:Id)?)(?![a-z])");

    /**
     * n, the repository half: a Spring Data repository interface whose derived query names such a column ({@code findByAccountIdAndName}, {@code deleteByKeyNs...}), and a
     * {@code @Table} entity (a class or a record) that has a field for one. The personal edition has one user on one PC, so no key has such a column.
     */
    static List<Violation> multiUserNames(JavaSources.Unit unit) {
        List<Violation> found = new ArrayList<>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree node, Void unused) {
                boolean entity = node.getModifiers().getAnnotations().stream().anyMatch(annotation -> "Table".equals(typeName(annotation.getAnnotationType())));
                boolean repository = node.getKind() == Tree.Kind.INTERFACE && node.getImplementsClause().stream().anyMatch(parent -> typeName(parent).endsWith("Repository"));
                for (Tree member : node.getMembers()) {
                    if (entity && member instanceof VariableTree field && MULTI_USER_FIELD.matcher(field.getName()).matches()) {
                        found.add(unit.violation(MULTI_USER_COLUMNS, field));
                    }
                    if (repository && member instanceof MethodTree method && method.getBody() == null && !hasQuery(method) && MULTI_USER_DERIVED.matcher(method.getName()).find()) {
                        found.add(unit.violation(MULTI_USER_COLUMNS, method));
                    }
                }
                return super.visitClass(node, unused);
            }

            private boolean hasQuery(MethodTree method) {
                return method.getModifiers().getAnnotations().stream().anyMatch(annotation -> "Query".equals(typeName(annotation.getAnnotationType())));
            }
        }.scan(unit.tree(), null);
        return found;
    }
}
