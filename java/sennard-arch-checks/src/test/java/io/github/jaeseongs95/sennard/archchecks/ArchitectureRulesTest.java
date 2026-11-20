// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreeScanner;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;

/**
 * The 6.0.0 design rules checked against the repository's own sources, SQL and build scripts (docs/sennard60-architecture-checks.md). One test per rule;
 * each one fails with the file:line list when the count differs from its ceiling in ceilings.properties.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArchitectureRulesTest {
    /** The test programs whose subject is a child process's own standard output; the one place System.out is allowed. */
    private static final Set<String> CONSOLE_PROBES = Set.of("Utf8BoundaryProbe.java", "CodexAppServerProbe.java");
    private static final Set<String> CONSOLE_RULES = Set.of(SourceRules.CONSOLE_OUTPUT.id(), SourceRules.PRINT_STACK_TRACE.id());

    private final Ratchet ratchet = new Ratchet();
    private Path javaRoot;
    private Path repository;
    private List<JavaSources.Unit> productSources;
    private List<JavaSources.Unit> testSources;
    private Map<String, String> migrations;
    private Map<String, String> buildScripts;
    private SqlRules.Context context;
    private Map<String, Integer> sqlHelpers;

    @BeforeAll
    void read() throws IOException {
        javaRoot = Path.of(System.getProperty("sennard.arch.root"));
        repository = Path.of(System.getProperty("sennard.arch.repository"));
        List<Path> modules = Stream.of(System.getProperty("sennard.arch.modules").split(",")).map(Path::of).toList();
        productSources = JavaSources.parseFiles(javaRoot, files(modules, "src/main/java", ".java"));
        testSources = JavaSources.parseFiles(javaRoot, files(modules, "src/test/java", ".java"));
        migrations = contents(files(modules, "src/main/resources", ".sql"));
        List<Path> scripts = new ArrayList<>(modules.stream().map(module -> module.resolve("build.gradle.kts")).filter(Files::isRegularFile).toList());
        scripts.add(javaRoot.resolve("build.gradle.kts"));
        buildScripts = contents(scripts);
        context = SqlRules.context(migrations);
        sqlHelpers = SourceRules.sqlHelpers(productSources);
    }

    private List<Path> files(List<Path> modules, String folder, String suffix) throws IOException {
        List<Path> found = new ArrayList<>();
        for (Path module : modules) {
            Path base = folder.isEmpty() ? module : module.resolve(folder);
            if (Files.isDirectory(base)) {
                try (Stream<Path> walk = Files.walk(base)) {
                    walk.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(suffix)).forEach(found::add);
                }
            }
        }
        found.sort(null);
        return found;
    }

    private Map<String, String> contents(List<Path> files) throws IOException {
        Map<String, String> read = new TreeMap<>();
        for (Path file : files) {
            read.put(JavaSources.relative(javaRoot, file), Files.readString(file, StandardCharsets.UTF_8));
        }
        return read;
    }

    private DynamicTest rule(String id, Supplier<List<Violation>> finder) {
        return DynamicTest.dynamicTest(id, () -> ratchet.hold(id, finder.get()));
    }

    @TestFactory
    List<DynamicTest> rules() {
        List<DynamicTest> tests = new ArrayList<>();
        tests.add(rule("a-package-cycles-direct", () -> PackageCycles.directCycles("a-package-cycles-direct", classDirectories())));
        for (SourceRules.Rule rule : SourceRules.ALL) {
            tests.add(rule(rule.id(), () -> sourceViolations(rule)));
        }
        tests.add(rule("h-managed-version", this::managedVersions));
        tests.add(rule("h-boot-version-copy", this::bootVersionCopies));
        tests.add(rule("j-sql-trigger", () -> perMigration((file, sql) -> SqlRules.triggers("j-sql-trigger", file, sql))));
        tests.add(rule("l-sql-logic-object", () -> perMigration((file, sql) -> SqlRules.logicObjects("l-sql-logic-object", file, sql))));
        tests.add(rule("l-sql-udf-call", () -> sqlHits("l-sql-udf-call", SqlRules.Kind.UDF_CALL)));
        tests.add(rule("m-pg-syntax", () -> sqlHits("m-pg-syntax", SqlRules.Kind.PG_SYNTAX)));
        tests.add(rule("m-rls", () -> sqlHits("m-rls", SqlRules.Kind.RLS)));
        tests.add(rule("m-advisory-lock", () -> sqlHits("m-advisory-lock", SqlRules.Kind.ADVISORY)));
        tests.add(rule("sql-limit-only-builder", () -> rowLimitsOutsideBuilder(productSources)));
        // One user on one PC: no account, tenant or namespace column comes back into the migrations, the SQL of the code or its repositories.
        tests.add(rule(SourceRules.MULTI_USER_COLUMNS, this::multiUserColumns));
        // The verbs the database's grants used to allow per table, held by the code now (I4, I5).
        tests.add(rule("w-sql-write-verb", this::writeVerbs));
        // A host's name (the label its hooks post under, the name its sessions are stored under) is written only in the file that defines the host.
        tests.add(rule(HostRules.ID, () -> HostRules.find(productSources)));
        // A failure's reason is a constant of a code enum, not a string; the answer to a failure is one complete switch over the sealed categories.
        tests.add(rule(ExceptionRules.STRING_CODE, () -> ExceptionRules.stringCodes(productSources)));
        tests.add(rule(ExceptionRules.SWITCH_DEFAULT, () -> ExceptionRules.switchDefaults(productSources)));
        // What the repository may carry besides Java: only the user's three exceptions (gradlew, the Java client as a hook command, ./gradlew in CI).
        tests.add(rule(RepositoryRules.SCRIPT_EXECUTABLES, () -> repositoryRule(RepositoryRules::scriptExecutables)));
        tests.add(rule(RepositoryRules.HOOK_COMMAND, () -> repositoryRule(RepositoryRules::hookCommands)));
        tests.add(rule(RepositoryRules.CI_RUN, () -> repositoryRule(RepositoryRules::ciRuns)));
        return tests;
    }

    static List<Violation> rowLimitsOutsideBuilder(List<JavaSources.Unit> productSources) {
        List<Violation> found = new ArrayList<>();
        for (JavaSources.Unit unit : productSources) {
            if (unit.file().endsWith("/service/database/SqlBuilder.java")) {
                continue;
            }
            new TreeScanner<Void, Void>() {
                private boolean rowLimit(String value) {
                    return java.util.regex.Pattern.compile("(?i)\\bFETCH\\s+(?:FIRST|NEXT)\\b|\\bLIMIT\\s+(?:[:?0-9]|$)").matcher(value).find();
                }

                @Override
                public Void visitLiteral(LiteralTree literal, Void unused) {
                    if (literal.getValue() instanceof String value && rowLimit(value)) {
                        found.add(unit.violation("sql-limit-only-builder", literal));
                    }
                    return super.visitLiteral(literal, unused);
                }

                @Override
                public Void visitBinary(BinaryTree binary, Void unused) {
                    if (binary.getKind() == Tree.Kind.PLUS && binary.getLeftOperand() instanceof LiteralTree left
                            && binary.getRightOperand() instanceof LiteralTree right && left.getValue() instanceof String a
                            && right.getValue() instanceof String b && !rowLimit(a) && !rowLimit(b) && rowLimit(a + b)) {
                        found.add(unit.violation("sql-limit-only-builder", binary));
                    }
                    return super.visitBinary(binary, unused);
                }
            }.scan(unit.tree(), null);
        }
        return found;
    }

    /** The rules that read files of the whole repository: the repository root is the parent of the java folder. */
    private List<Violation> repositoryRule(RepositoryReader rule) {
        try {
            return rule.find(repository);
        } catch (IOException cause) {
            throw new java.io.UncheckedIOException(cause);
        }
    }

    @FunctionalInterface
    private interface RepositoryReader {
        List<Violation> find(Path repository) throws IOException;
    }

    /** The rule of the host names must recognise the product's hosts: a definition that stopped being written as a new HostId would leave the rule with nothing to compare. */
    @Test
    void theHostNameRuleRecognisesTheHostsOfTheProduct() {
        assertTrue(HostRules.definedNames(productSources).keySet().containsAll(Set.of("claude", "claude-code", "codex", "agy", "antigravity", "gemini")),
                "the names the product's host definitions write: " + HostRules.definedNames(productSources));
    }

    /** The two boundaries that answer a failure must be found by rule t: a switch over the seven categories in each, so the rule is not silent because the switches were rewritten. */
    @Test
    void theCompleteSwitchRuleSeesTheTwoBoundariesOfTheProduct() {
        for (String boundary : List.of("/SennardToolResults.java", "/SennardExceptionHandler.java")) {
            JavaSources.Unit unit = productSources.stream().filter(candidate -> candidate.file().endsWith(boundary)).findFirst().orElseThrow();
            assertTrue(ExceptionRules.switchesOnCategories(List.of(unit)) >= 1, boundary + " has a switch over the categories of SennardException");
        }
    }

    /** A boundary that matches no source is a package that was renamed or removed: the list of places left out of the six rules must be corrected, not left to leave a gap. */
    @Test
    void everyBoundaryOfTheNarrowedRulesMatchesASourceOfTheProduct() {
        for (Boundaries.Boundary boundary : Boundaries.LIST) {
            assertTrue(productSources.stream().anyMatch(unit -> boundary.matches(unit.file())), "no source of the product is in the boundary " + boundary.prefix() + " (" + boundary.reason() + ")");
        }
    }

    /** A rule that recognises no repository finds nothing: the board's repository (the first of the rewritten areas) must be one the repository half of rule n reads, and the domain rule must see the domain. */
    @Test
    void theMultiUserRuleReadsTheBoardRepositoryAndTheDomainRuleSeesTheDomain() {
        JavaSources.Unit repository = productSources.stream().filter(unit -> unit.file().endsWith("/BoardSessionRepository.java")).findFirst().orElseThrow();
        boolean extendsRepository = repository.text(repository.lines().indexOf(repository.lines().stream().filter(line -> line.contains("interface BoardSessionRepository")).findFirst().orElseThrow()) + 1)
                .contains("Repository<BoardSession,");
        assertTrue(extendsRepository, "the repository's declaration is still the one the rule reads");
        assertTrue(productSources.stream().filter(unit -> unit.file().startsWith(SourceRules.DOMAIN_SOURCES)).count() >= 10, "the domain's sources are among those the rule reads");
    }

    private List<Path> classDirectories() {
        return Stream.of(System.getProperty("sennard.arch.classes").split(",")).map(Path::of).filter(Files::isDirectory).toList();
    }

    private List<Violation> sourceViolations(SourceRules.Rule rule) {
        List<Violation> found = new ArrayList<>();
        productSources.forEach(unit -> found.addAll(Boundaries.find(rule, unit, context)));
        if (CONSOLE_RULES.contains(rule.id())) {
            testSources.stream().filter(unit -> CONSOLE_PROBES.stream().noneMatch(unit.file()::endsWith)).forEach(unit -> found.addAll(rule.find(unit, context)));
        }
        return found;
    }

    private List<Violation> perMigration(java.util.function.BiFunction<String, String, List<Violation>> rule) {
        List<Violation> found = new ArrayList<>();
        migrations.forEach((file, sql) -> found.addAll(rule.apply(file, sql)));
        return found;
    }

    /** The hits of one SQL kind in the migrations and in the SQL strings of the code. */
    private List<Violation> sqlHits(String rule, SqlRules.Kind kind) {
        List<Violation> found = new ArrayList<>(perMigration((file, sql) -> SqlRules.migrationHits(rule, file, sql, kind, context)));
        for (JavaSources.Unit unit : productSources) {
            for (SqlRules.Statement statement : SourceRules.sqlStatements(unit, sqlHelpers)) {
                for (SqlRules.Hit hit : SqlRules.find(statement.pieces(), kind, context)) {
                    found.add(new Violation(rule, unit.file(), hit.line(), unit.text(hit.line()) + "   [" + hit.what() + "]"));
                }
            }
        }
        return found;
    }

    /** n: the migrations and the SQL strings of the code (the same reading as the m rules), then the derived queries and entities of the repositories. */
    private List<Violation> multiUserColumns() {
        List<Violation> found = new ArrayList<>(sqlHits(SourceRules.MULTI_USER_COLUMNS, SqlRules.Kind.MULTI_USER_COLUMN));
        productSources.forEach(unit -> found.addAll(SourceRules.multiUserNames(unit)));
        return found;
    }

    private List<Violation> writeVerbs() {
        List<Violation> found = new ArrayList<>();
        int examined = 0;
        for (JavaSources.Unit unit : productSources) {
            for (SqlRules.Statement statement : SourceRules.sqlStatements(unit, sqlHelpers)) {
                for (String problem : SqlRules.writeProblems(statement.text(), unit.file())) {
                    found.add(new Violation("w-sql-write-verb", unit.file(), statement.line(), unit.text(statement.line()) + "   [" + problem + "]"));
                }
                if (statement.text().stripLeading().regionMatches(true, 0, "insert", 0, 6)) {
                    examined++;
                }
            }
        }
        if (examined < 10) {
            throw new IllegalStateException("only " + examined + " INSERT statements were read from the code; the SQL strings are no longer being found");
        }
        return found;
    }

    private List<Violation> managedVersions() {
        BuildScriptRules.Managed managed = BuildScriptRules.managed(Path.of(System.getProperty("sennard.arch.boot-bom")));
        List<Violation> found = new ArrayList<>();
        buildScripts.forEach((file, script) -> found.addAll(BuildScriptRules.managedVersions("h-managed-version", file, script, managed)));
        return found;
    }

    private List<Violation> bootVersionCopies() {
        String version = BuildScriptRules.bootVersion(buildScripts.get("build.gradle.kts"));
        if (version == null) {
            throw new IllegalStateException("build.gradle.kts of the root has no val springBootVersion; the Boot version has no single place to be written");
        }
        List<Violation> found = new ArrayList<>();
        buildScripts.forEach((file, script) -> found.addAll(BuildScriptRules.bootVersionCopies("h-boot-version-copy", file, script, version)));
        return found;
    }
}
