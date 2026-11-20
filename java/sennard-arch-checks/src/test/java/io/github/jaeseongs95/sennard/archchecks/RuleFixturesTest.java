// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cases of the checks themselves: for each rule a source that breaks it (red: the check finds exactly these places) and a source that keeps
 * to it, including the near misses the rule must leave alone (green: nothing found). The rules are only worth their ceilings if these hold.
 */
class RuleFixturesTest {
    private static final String MIGRATION = """
            CREATE TABLE sennard.item (
                name text NOT NULL PRIMARY KEY
            );
            CREATE TABLE sennard.shared (
                key text NOT NULL PRIMARY KEY
            );
            CREATE FUNCTION sennard.stamp() RETURNS bigint LANGUAGE sql AS $$ SELECT 1 $$;
            """;
    private static final SqlRules.Context CONTEXT = SqlRules.context(Map.of("V1__fixture.sql", MIGRATION));

    private static List<Violation> run(SourceRules.Rule rule, String source) {
        List<Violation> found = new ArrayList<>();
        JavaSources.parse(Map.of("Fixture.java", source)).forEach(unit -> found.addAll(rule.find(unit, CONTEXT)));
        return found;
    }

    /** The lines the rule reports, so a case says where, not only how many. */
    private static List<Integer> lines(SourceRules.Rule rule, String source) {
        return run(rule, source).stream().map(Violation::line).distinct().sorted().toList();
    }

    // ------------------------------------------------------------------------------------------------------------------------------ b

    @Test
    void osBranchingFindsTheOsNameFileSeparatorAndFileSystemSeparator() {
        assertEquals(List.of(3, 4, 5, 6), lines(SourceRules.OS_BRANCHING, """
                class A {
                    void m(java.nio.file.FileSystem fs) {
                        boolean w = System.getProperty("os.name").startsWith("win");
                        String s = java.io.File.separator;
                        char c = File.separatorChar;
                        String t = fs.getSeparator();
                    }
                }
                """));
    }

    @Test
    void osBranchingLeavesThePathApiAlone() {
        assertEquals(List.of(), lines(SourceRules.OS_BRANCHING, """
                class A {
                    Object m(java.nio.file.Path base) {
                        return base.resolve("a").resolve("b").toAbsolutePath().toRealPath();
                    }
                }
                """));
    }

    // ------------------------------------------------------------------------------------------------------------------------------ c

    @Test
    void valueGetenvAndPropertiesAreCountedApart() {
        String source = """
                class A {
                    @Value("${x}") String a;
                    String b = System.getenv("HOME");
                    String c = System.getProperty("sennard.mode");
                    String d = System.getProperty("user.home");
                    boolean e = Boolean.getBoolean("sennard.flag");
                    String f = System.getProperty(key);
                }
                """;
        assertEquals(List.of(2), lines(SourceRules.VALUE_ANNOTATION, source));
        assertEquals(List.of(3), lines(SourceRules.GETENV, source));
        assertEquals(List.of(4, 6, 7), lines(SourceRules.GETPROPERTY, source));
        assertEquals(List.of(5), lines(SourceRules.GETPROPERTY_JVM, source));
    }

    @Test
    void configurationPropertiesAreNotValueAnnotations() {
        assertEquals(List.of(), lines(SourceRules.VALUE_ANNOTATION, """
                @ConfigurationProperties("sennard")
                record Settings(java.time.Duration timeout) {
                }
                """));
    }

    // ------------------------------------------------------------------------------------------------------------------------------ d

    @Test
    void staticStateIsFoundAndConstantsAreNot() {
        assertEquals(List.of(2, 3, 4, 5, 6), lines(SourceRules.STATEFUL_STATIC, """
                class A {
                    static int counter;
                    static final SecureRandom RANDOM = new SecureRandom();
                    static final Object LOCK = new Object();
                    static final Map<String, String> CACHE = new HashMap<>();
                    private static final List<String> ITEMS = new ArrayList<>();
                }
                """));
        assertEquals(List.of(), lines(SourceRules.STATEFUL_STATIC, """
                class A {
                    static final String NAME = "x";
                    static final int LIMIT = 3;
                    static final java.time.Duration WAIT = java.time.Duration.ofSeconds(1);
                    static final List<String> NAMES = List.of("a", "b");
                    static final Map<String, Integer> ORDER = Map.of("a", 1);
                    static final Pattern WORD = Pattern.compile("a");
                    private int instanceField;
                    enum Kind { FIRST(1), SECOND(2); final int n; Kind(int n) { this.n = n; } }
                }
                """));
    }

    // ------------------------------------------------------------------------------------------------------------------------------ e

    @Test
    void consoleOutputAndPrintStackTraceAreFound() {
        String source = """
                class A {
                    void m(Exception e) {
                        System.out.println("x");
                        System.err.print("y");
                        e.printStackTrace();
                    }
                }
                """;
        assertEquals(List.of(3, 4), lines(SourceRules.CONSOLE_OUTPUT, source));
        assertEquals(List.of(5), lines(SourceRules.PRINT_STACK_TRACE, source));
        assertEquals(List.of(), lines(SourceRules.CONSOLE_OUTPUT, """
                class A {
                    private static final Logger log = LoggerFactory.getLogger(A.class);
                    void m(Exception e) {
                        log.error("failed", e);
                    }
                }
                """));
    }

    // ------------------------------------------------------------------------------------------------------------------------------ p

    @Test
    void anOptionalAsAFieldAComponentAParameterOrACollectionElementIsFound() {
        assertEquals(List.of(2, 3, 4, 5, 7, 8, 9, 10), lines(SourceRules.OPTIONAL_NOT_RETURN, """
                class A {
                    private Optional<String> field;
                    java.util.Optional<Path> qualified;
                    OptionalInt count;
                    List<Optional<String>> names;
                    record R(String id,
                            Optional<String> component) { }
                    void m(Optional<String> parameter) { }
                    void n(Map<String, Optional<Path>> byName) { }
                    static void o(java.util.OptionalLong size, String ok) { }
                }
                """));
    }

    @Test
    void anOptionalAsAReturnTypeALocalOrALambdaParameterIsLeftAlone() {
        assertEquals(List.of(), lines(SourceRules.OPTIONAL_NOT_RETURN, """
                class A {
                    Optional<String> find(String id) {
                        Optional<String> local = lookup(id);
                        names.forEach((Optional<String> each) -> { });
                        return local;
                    }
                    private Supplier<Optional<Path>> installed;
                    private List<String> names;
                    record R(String id, List<String> parts) { }
                    void m(Supplier<Optional<Path>> installed, Function<String, Optional<String>> finder) { }
                }
                """));
    }

    @Test
    void orElseNullIsFoundAndOtherOrElseCallsAreNot() {
        assertEquals(List.of(3, 4), lines(SourceRules.OR_ELSE_NULL, """
                class A {
                    void m(Optional<String> a, Binder b) {
                        String x = a.orElse(null);
                        String y = find().map(Item::name).orElse(null);
                        String z = a.orElse("none");
                        String w = a.orElse(fallback);
                        String v = a.orElseGet(() -> null);
                        String u = a.orElseThrow();
                    }
                }
                """));
    }

    // ------------------------------------------------------------------------------------------------------------------------------ f

    @Test
    void swallowedCatchesAreFound() {
        assertEquals(List.of(5, 9, 14), lines(SourceRules.SWALLOWED_CATCH, """
                class A {
                    Object m() {
                        try {
                            return load();
                        } catch (IOException e) {
                        }
                        try {
                            return load();
                        } catch (IOException e) {
                            return null;
                        }
                        try {
                            return load();
                        } catch (IOException e) {
                            return fallback(e.getMessage());
                        }
                        return null;
                    }
                }
                """));
    }

    @Test
    void loggedRethrownAndPassedOnCatchesAreLeftAlone() {
        assertEquals(List.of(), lines(SourceRules.SWALLOWED_CATCH, """
                class A {
                    void m() {
                        try {
                            load();
                        } catch (IOException e) {
                            log.error("cannot load", e);
                        }
                        try {
                            load();
                        } catch (IOException e) {
                            throw new IllegalStateException("cannot load", e);
                        }
                        try {
                            load();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        try {
                            load();
                        } catch (IOException e) {
                            failures.add(e);
                        }
                    }
                }
                """));
    }

    // ------------------------------------------------------------------------------------------------------------------------------ g

    @Test
    void stringSwitchesAndComparisonsWithKindWordsAreFound() {
        assertEquals(List.of(3, 7, 11), lines(SourceRules.STRING_SWITCH, """
                class A {
                    void m(String s) {
                        switch (s) {
                            case "passed": break;
                            default: break;
                        }
                        int n = switch (s) {
                            case "PASS" -> 1;
                            default -> 0;
                        };
                        switch (s) { case "a", "b" -> n = 2; default -> n = 3; }
                    }
                }
                """));
        assertEquals(List.of(4, 5, 6, 7, 8), lines(SourceRules.STRING_EQUALS, """
                class A {
                    static final String PASSED = "passed";
                    boolean m(String s) {
                        return "PASS".equals(s)
                            || s.equals("passed")
                            || s.equalsIgnoreCase("x1")
                            || Objects.equals(s, "BLOCKED")
                            || s.equals(PASSED);
                    }
                }
                """));
    }

    @Test
    void enumsConstantsAndPunctuationAreNotStringKinds() {
        assertEquals(List.of(), lines(SourceRules.STRING_SWITCH, """
                class A {
                    int m(Status s) {
                        return switch (s) { case PASSED -> 1; case FAILED -> 2; };
                    }
                }
                """));
        assertEquals(List.of(), lines(SourceRules.STRING_EQUALS, """
                class A {
                    boolean m(String s, String other, Status status) {
                        return s.equals("") || s.equals("/") || s.equals(".") || s.equals(other) || status == Status.PASSED;
                    }
                }
                """));
    }

    // ------------------------------------------------------------------------------------------------------------------------------ i

    @Test
    void pathStringHandlingIsFound() {
        assertEquals(List.of(3, 4, 5, 6, 7, 8, 9, 10), lines(SourceRules.PATH_STRING, """
                class A {
                    void m(String p, String parent, String a, String b) {
                        String x = p.replace('\\\\', '/');
                        String y = p.replaceAll("\\\\\\\\", "/");
                        Pattern drive = Pattern.compile("^[A-Za-z]:[\\\\\\\\/].*");
                        boolean z = p.startsWith(parent + "/");
                        int i = p.lastIndexOf('/');
                        String joined = a + "/" + b;
                        boolean d = p.charAt(1) == ':';
                        String[] parts = p.split("/");
                    }
                }
                """));
    }

    @Test
    void thePathApiAndOtherSplitsAreNotPathStringHandling() {
        assertEquals(List.of(), lines(SourceRules.PATH_STRING, """
                class A {
                    Object m(java.nio.file.Path base, String csv) {
                        String[] cells = csv.split(",");
                        return base.resolve("a").normalize().startsWith(base);
                    }
                }
                """));
    }

    // ------------------------------------------------------------------------------------------------------------------------------ h

    @Test
    void aVersionNextToAManagedLibraryIsFoundAndTheBomAndFreeLibrariesAreNot() {
        BuildScriptRules.Managed managed = new BuildScriptRules.Managed(Set.of("org.postgresql:postgresql"), Set.of("tools.jackson", "org.springframework.ai"));
        String script = """
                dependencies {
                    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
                    implementation("org.postgresql:postgresql:42.7.14")
                    implementation("org.postgresql:postgresql")
                    implementation("tools.jackson.core:jackson-databind:3.0.1")
                    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc:2.0.2")
                    implementation("org.apache.commons:commons-compress:1.28.0")
                }
                """;
        assertEquals(List.of(3, 5, 6), BuildScriptRules.managedVersions("h", "build.gradle.kts", script, managed).stream().map(Violation::line).toList());
    }

    @Test
    void theBootVersionWrittenASecondTimeIsFound() {
        String script = """
                plugins {
                    id("org.springframework.boot") version "4.1.2"
                }
                """;
        assertEquals(List.of(2), BuildScriptRules.bootVersionCopies("h", "sennard-x/build.gradle.kts", script, "4.1.2").stream().map(Violation::line).toList());
        assertEquals(List.of(), BuildScriptRules.bootVersionCopies("h", "build.gradle.kts", "val springBootVersion = \"4.1.2\"\n", "4.1.2"));
    }

    // ------------------------------------------------------------------------------------------------------------------- j, l: migrations

    private static List<Integer> migrationLines(String sql, java.util.function.BiFunction<String, String, List<Violation>> rule) {
        return rule.apply("V1.sql", sql).stream().map(Violation::line).toList();
    }

    @Test
    void triggersAreFoundAndAFunctionThatReturnsTriggerIsCountedByJNotByL() {
        String sql = """
                -- CREATE TRIGGER in a comment is not a trigger
                CREATE FUNCTION audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$;
                CREATE TRIGGER audit_row BEFORE INSERT ON t FOR EACH ROW EXECUTE FUNCTION audit();
                INSERT INTO log VALUES ('create trigger in a literal');
                """;
        assertEquals(List.of(2, 3), migrationLines(sql, (file, text) -> SqlRules.triggers("j", file, text)));
        assertEquals(List.of(), migrationLines(sql, (file, text) -> SqlRules.logicObjects("l", file, text)));
    }

    @Test
    void functionsProceduresRulesAggregatesOperatorsAndDoBlocksAreLogicObjects() {
        String sql = """
                CREATE FUNCTION f() RETURNS int LANGUAGE sql AS $$ SELECT 1 $$;
                CREATE OR REPLACE FUNCTION g() RETURNS int LANGUAGE sql AS $$ SELECT 2 $$;
                CREATE PROCEDURE p() LANGUAGE sql AS $$ SELECT 1 $$;
                CREATE RULE r AS ON INSERT TO t DO INSTEAD NOTHING;
                CREATE AGGREGATE agg(int) (SFUNC = int4pl, STYPE = int);
                CREATE OPERATOR === (LEFTARG = int, RIGHTARG = int, FUNCTION = int4eq);
                DO $$ BEGIN PERFORM 1; END $$;
                """;
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), migrationLines(sql, (file, text) -> SqlRules.logicObjects("l", file, text)));
    }

    @Test
    void declarationsAndBuiltInFunctionsAreNotLogicObjects() {
        String sql = """
                CREATE TABLE t (id int NOT NULL PRIMARY KEY, created timestamp DEFAULT now(), CHECK (id > 0));
                CREATE UNIQUE INDEX t_id ON t (id);
                GRANT SELECT ON t TO app;
                SELECT max(id), min(id), count(*), sum(id), coalesce(id, 0), lower('A') FROM t;
                """;
        assertEquals(List.of(), migrationLines(sql, (file, text) -> SqlRules.logicObjects("l", file, text)));
        assertEquals(List.of(), migrationLines(sql, (file, text) -> SqlRules.triggers("j", file, text)));
        assertEquals(List.of(), migrationLines(sql, (file, text) -> SqlRules.migrationHits("l-sql-udf-call", file, text, SqlRules.Kind.UDF_CALL, CONTEXT)));
    }

    @Test
    void aCallOfAUserDefinedFunctionIsCountedAndItsDefinitionIsNot() {
        String sql = """
                CREATE FUNCTION sennard.stamp() RETURNS bigint LANGUAGE sql AS $$ SELECT 1 $$;
                SELECT sennard.stamp();
                SELECT stamp();
                """;
        assertEquals(List.of(2, 3), migrationLines(sql, (file, text) -> SqlRules.migrationHits("l-sql-udf-call", file, text, SqlRules.Kind.UDF_CALL, CONTEXT)));
    }

    // ---------------------------------------------------------------------------------------------------------- m: PostgreSQL-only SQL

    private static List<String> hits(String sql, SqlRules.Kind kind) {
        return SqlRules.find(List.of(new SqlRules.Piece(sql, 1)), kind, CONTEXT).stream().map(SqlRules.Hit::what).toList();
    }

    @Test
    void postgreSqlOnlySyntaxIsFound() {
        assertEquals(List.of("cast operator ::"), hits("SELECT a::text FROM t", SqlRules.Kind.PG_SYNTAX));
        assertEquals(List.of("ILIKE"), hits("SELECT a FROM t WHERE a ILIKE ?", SqlRules.Kind.PG_SYNTAX));
        assertEquals(List.of("ON CONFLICT (standard: MERGE)", "RETURNING"), hits("INSERT INTO t(a) VALUES (?) ON CONFLICT DO NOTHING RETURNING a", SqlRules.Kind.PG_SYNTAX));
        assertEquals(List.of("full text search", "full text search"), hits("SELECT a FROM t WHERE to_tsvector(a) @@ to_tsquery(?)", SqlRules.Kind.PG_SYNTAX).stream().limit(2).toList());
        assertEquals(List.of("SERIAL (standard: GENERATED ... AS IDENTITY)"), hits("CREATE TABLE t (id SERIAL PRIMARY KEY)", SqlRules.Kind.PG_SYNTAX));
        assertEquals(List.of("json operator"), hits("SELECT doc ->> 'k' FROM t", SqlRules.Kind.PG_SYNTAX));
        assertEquals(List.of("pg_* function or catalog"), hits("SELECT pg_size_pretty(1)", SqlRules.Kind.PG_SYNTAX));
        assertEquals(List.of("partial index (CREATE INDEX ... WHERE)"), hits("CREATE UNIQUE INDEX i ON t (a) WHERE a IS NOT NULL;", SqlRules.Kind.PG_SYNTAX));
        assertEquals(List.of("advisory lock"), hits("SELECT pg_advisory_xact_lock(?, ?)", SqlRules.Kind.ADVISORY));
        assertEquals(List.of(), hits("SELECT pg_advisory_xact_lock(?, ?)", SqlRules.Kind.PG_SYNTAX));
    }

    @Test
    void rowLevelSecurityIsCountedApart() {
        assertEquals(List.of("row level security policy", "row level security switch", "row level security switch", "set_config call"), hits("""
                ALTER TABLE t ENABLE ROW LEVEL SECURITY;
                ALTER TABLE t FORCE ROW LEVEL SECURITY;
                CREATE POLICY p ON t USING (a = set_config('x', 'y', true));
                """, SqlRules.Kind.RLS));
        assertEquals(List.of("current_setting function"), hits("SELECT current_setting('server_encoding')", SqlRules.Kind.PG_SYNTAX));
    }

    @Test
    void standardSqlBuiltInFunctionsAggregatesAndLiteralsAreNotPostgreSqlOnly() {
        String sql = """
                SELECT max(a), min(b), count(*), sum(c), coalesce(d, 0), lower(e), upper(f), CAST(g AS VARCHAR(10)), CURRENT_TIMESTAMP
                FROM t JOIN u ON u.t_id = t.id
                WHERE t.name LIKE ? AND t.note = 'a::b ILIKE ON CONFLICT RETURNING' -- ::text ILIKE
                ORDER BY a FETCH FIRST 10 ROWS ONLY
                """;
        for (SqlRules.Kind kind : List.of(SqlRules.Kind.PG_SYNTAX, SqlRules.Kind.RLS, SqlRules.Kind.ADVISORY)) {
            assertEquals(List.of(), hits(sql, kind));
        }
    }

    @Test
    void rowLimitsAreAllowedOnlyInTheBuilder() {
        var sources = JavaSources.parse(Map.of(
                "module/src/service/database/SqlBuilder.java", "class SqlBuilder { String a = \"LIMIT \"; }",
                "Other.java", "class Other { String a = \"FETCH FIRST 1 ROWS ONLY\"; String b = \"LIMIT :n\"; String c = \"output limit reached\"; String d = \"FETCH \" + \"NEXT 1 ROWS ONLY\"; }"));
        assertEquals(3, ArchitectureRulesTest.rowLimitsOutsideBuilder(sources).size());
    }

    @Test
    void sqlStringsOfTheCodeAreReadThroughConstantsPartsAndHelpers() {
        String source = """
                class Store {
                    private static final String QUERY = "SELECT a::text FROM t";
                    private static final String SCOPE = "WHERE a = ? AND b = ?";
                    List<Object> one() { return jdbc.sql(QUERY).query().list(); }
                    Object two() { return jdbc.sql("SELECT x " + "FROM t " + SCOPE + " AND y ILIKE ?").query().single(); }
                    Object three() { return rows("INSERT INTO t(a) VALUES (?) RETURNING a"); }
                    Object rows(String sql) { return jdbc.sql(sql).query().list(); }
                    Object ok() { return jdbc.sql("SELECT max(a) FROM t WHERE b = ?").query().single(); }
                }
                """;
        List<JavaSources.Unit> units = JavaSources.parse(Map.of("Store.java", source));
        Map<String, Integer> helpers = SourceRules.sqlHelpers(units);
        assertEquals(Map.of("rows", 0), helpers);
        List<String> found = new ArrayList<>();
        for (SqlRules.Statement statement : SourceRules.sqlStatements(units.get(0), helpers)) {
            SqlRules.find(statement.pieces(), SqlRules.Kind.PG_SYNTAX, CONTEXT).forEach(hit -> found.add(hit.line() + " " + hit.what()));
        }
        assertEquals(List.of("2 cast operator ::", "5 ILIKE", "6 RETURNING"), found.stream().sorted().toList());
    }

    @Test
    void aConstantWrittenTwiceInOneStatementIsReadTwice() {
        String source = """
                class Store {
                    private static final String SCOPE = "WHERE a = ? AND b = ?";
                    Object one() { return jdbc.sql("DELETE FROM item " + SCOPE + " AND name IN (SELECT name FROM item " + SCOPE + ")").update(); }
                }
                """;
        List<JavaSources.Unit> units = JavaSources.parse(Map.of("Store.java", source));
        List<SqlRules.Statement> statements = SourceRules.sqlStatements(units.get(0), SourceRules.sqlHelpers(units));
        assertEquals(List.of("DELETE FROM item WHERE a = ? AND b = ? AND name IN (SELECT name FROM item WHERE a = ? AND b = ?)"),
                statements.stream().map(SqlRules.Statement::text).toList());
    }

    // ------------------------------------------------------------------------------------------------------------------------------ n

    @Test
    void theUserDefinedFunctionsAreReadFromTheMigrations() {
        assertEquals(Set.of("stamp"), CONTEXT.functionNames());
    }

    @Test
    void anAccountTenantOrNamespaceColumnOrTableInSqlIsFound() {
        assertEquals(List.of("account column or table"), hits("CREATE TABLE sennard.item (account_id uuid NOT NULL, name text)", SqlRules.Kind.MULTI_USER_COLUMN));
        assertEquals(List.of("namespace column (ns)", "namespace column (ns)"), hits("SELECT name FROM sennard.item i WHERE i.ns = ? AND ns = ?", SqlRules.Kind.MULTI_USER_COLUMN));
        assertEquals(List.of("tenant column or table"), hits("INSERT INTO item (tenant_id, name) VALUES (?, ?)", SqlRules.Kind.MULTI_USER_COLUMN));
        assertEquals(List.of("namespace column or table"), hits("ALTER TABLE sennard.item DROP COLUMN namespace", SqlRules.Kind.MULTI_USER_COLUMN));
        assertEquals(List.of("account column or table"), hits("DELETE FROM sennard.account", SqlRules.Kind.MULTI_USER_COLUMN));
        assertEquals(List.of(3), migrationLines("""
                CREATE TABLE sennard.item (
                    name text NOT NULL,
                    account_id uuid NOT NULL
                );
                """, (file, text) -> SqlRules.migrationHits(SourceRules.MULTI_USER_COLUMNS, file, text, SqlRules.Kind.MULTI_USER_COLUMN, CONTEXT)));
    }

    @Test
    void sqlThatNamesNoUserColumnOrOnlyMentionsOneInACommentOrALiteralIsLeftAlone() {
        assertEquals(List.of(), hits("SELECT name, seen_at FROM sennard.item WHERE name = ? AND owner_name = ?", SqlRules.Kind.MULTI_USER_COLUMN));
        assertEquals(List.of(), hits("SELECT 1 FROM item WHERE note = 'account_id ns tenant namespace' -- account_id ns tenant namespace", SqlRules.Kind.MULTI_USER_COLUMN));
        assertEquals(List.of(), hits("SELECT lines, turns, nsx, accounting, tenants, namespaces FROM item", SqlRules.Kind.MULTI_USER_COLUMN), "a longer identifier is another name");
    }

    @Test
    void aRepositoryWithAUserColumnInItsDerivedQueriesOrItsEntityIsFoundAndOneWithoutIsLeftAlone() {
        List<Violation> found = new ArrayList<>();
        JavaSources.parse(Map.of("Fixture.java", """
                @Table("item")
                record Item(@Id Long id, java.util.UUID accountId, String name) {
                }
                @Table("widget")
                class Widget {
                    String ns;
                    String name;
                }
                @Table("gadget")
                record Gadget(@Id Long id, String name) {
                }
                interface ItemRepository extends Repository<Item, Long> {
                    java.util.List<Item> findByAccountIdAndName(java.util.UUID account, String name);
                    java.util.List<Item> findByKeyNsAndKeyName(String ns, String name);
                    java.util.List<Item> findByName(String name);
                    @Query("SELECT * FROM item WHERE name = :name")
                    java.util.List<Item> findByNsAndName(String name);
                    java.util.List<Item> findByNsfoo(String name);
                }
                """)).forEach(unit -> found.addAll(SourceRules.multiUserNames(unit)));
        assertEquals(List.of(2, 6, 13, 14), found.stream().map(Violation::line).distinct().sorted().toList(),
                "the entity fields accountId and ns, and the two derived queries; a query with its own @Query, a plain name and a longer word (Nsfoo) are left alone");
    }

    @Test
    void aStatementThatWritesATableWithAVerbItsFormerGrantsDidNotAllowIsFound() {
        assertEquals(List.of(), SqlRules.writeProblems("INSERT INTO sennard.user_prompt (prompt_id) VALUES (?)", "sennard-app/src/main/java/UserPromptWrites.java"));
        assertEquals(List.of("UPDATE on user_prompt (allowed: [insert])", "UPDATE on user_prompt outside UserPromptWrites.java"),
                SqlRules.writeProblems("UPDATE sennard.user_prompt SET prompt_text = ? WHERE prompt_id = ?", "sennard-app/src/main/java/Other.java"));
        assertEquals(List.of("DELETE on user_prompt (allowed: [insert])", "DELETE on user_prompt outside UserPromptWrites.java"),
                SqlRules.writeProblems("DELETE FROM sennard.user_prompt WHERE prompt_id = ?", "sennard-app/src/main/java/Writer.java"));
        assertEquals(List.of("INSERT on user_prompt outside UserPromptWrites.java"), SqlRules.writeProblems("INSERT INTO sennard.user_prompt (a) VALUES (?)", "A.java"));
        assertEquals(List.of("DELETE on transcript_anchor (allowed: [insert])"), SqlRules.writeProblems("DELETE FROM sennard.transcript_anchor WHERE anchor_id = ?", "TranscriptAnchorRepository.java"));
        assertEquals(List.of("INSERT on glossary_entries (allowed: [])"), SqlRules.writeProblems("INSERT INTO sennard.glossary_entries (entry_id) VALUES (?)", "A.java"));
        assertEquals(List.of(), SqlRules.writeProblems("UPDATE sennard.turn_recap SET recap_text = ? WHERE turn_id = ?", "A.java"), "a recap may be replaced");
        assertEquals(List.of(), SqlRules.writeProblems("SELECT * FROM sennard.user_prompt WHERE prompt_id = ?", "A.java"), "a read is not a write");
        assertEquals(List.of(), SqlRules.writeProblems("UPDATE sennard.board_sessions SET summary = ? WHERE session_id = ?", "A.java"), "a table that was fully writable has no restriction");
    }

    // ------------------------------------------------------------------------------------------------------------------------------ o

    private static List<Integer> domainLines(String file, String source) {
        List<Violation> found = new ArrayList<>();
        JavaSources.parse(Map.of(file, source)).forEach(unit -> found.addAll(SourceRules.DOMAIN_IMPORTS.find(unit, CONTEXT)));
        return found.stream().map(Violation::line).distinct().sorted().toList();
    }

    private static final String DOMAIN_FILE = "sennard-domain/src/main/java/io/github/Fixture.java";

    @Test
    void aDomainSourceThatImportsTheFrameworkTheDatabaseOrTheJsonTreeIsFound() {
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 9, 10), domainLines(DOMAIN_FILE, """
                import org.springframework.stereotype.Service;
                import org.springframework.util.*;
                import java.sql.Timestamp;
                import javax.sql.DataSource;
                import tools.jackson.databind.JsonNode;
                import static org.springframework.util.Assert.notNull;
                class Fixture {
                    private final java.util.List<String> fine = null;
                    private org.springframework.context.MessageSource source;
                    Object read(tools.jackson.databind.json.JsonMapper mapper) { return java.sql.Types.INTEGER; }
                }
                """), "imports (plain, wildcard, static) and full names in the code; line 10 holds two uses and is one place");
    }

    @Test
    void aDomainSourceThatUsesOnlyTheJdkAndTheAllowedAnnotationsIsLeftAlone() {
        assertEquals(List.of(), domainLines(DOMAIN_FILE, """
                import java.time.Instant;
                import java.util.Map;
                import java.text.MessageFormat;
                import tools.jackson.annotation.JsonProperty;
                import jakarta.validation.constraints.NotBlank;
                // import org.springframework.stereotype.Service; (a comment)
                /** {@link org.springframework.context.MessageSource} in a comment names it without using it. */
                class Fixture {
                    String springframework = "org.springframework.stereotype.Service and java.sql.Timestamp in a string";
                    Object sqlLike = java.util.Objects.hash("javax.sql");
                }
                """), "a similar name (java.text, java.util, tools.jackson.annotation), a comment and a string are not uses");
        assertEquals(List.of(), domainLines("sennard-app/src/main/java/io/github/Fixture.java", """
                import org.springframework.stereotype.Service;
                import java.sql.Timestamp;
                class Fixture {
                }
                """), "another module is free to use them: the rule is about the domain only");
    }

    // ------------------------------------------------------------------------------------------------------- the six rules and their boundaries

    private static final String HOST_FILE = "sennard-server/src/main/java/io/github/jaeseongs95/sennard/server/hook/host/Fixture.java";
    private static final String SKILLS_FILE = "sennard-server/src/main/java/io/github/jaeseongs95/sennard/server/skills/Fixture.java";
    private static final String INSTALL_FILE = "sennard-personal/src/main/java/io/github/jaeseongs95/sennard/personal/install/Fixture.java";
    /** One hit of each narrowed rule, each on its own line (3 to 8). */
    private static final String ONE_OF_EACH = """
            class A {
                void m(String s) {
                    boolean w = System.getProperty("os.name").startsWith("win");
                    String e = System.getenv("HOME");
                    String p = System.getProperty("sennard.mode");
                    switch (s) { case "run" -> { } default -> { } }
                    boolean k = s.equals("start");
                    String t = s.replace('\\\\', '/');
                }
            }
            """;

    /** The lines the narrowed rule reports in a file of the product, the boundaries of {@link Boundaries} applied. */
    private static List<Integer> counted(String id, String file, String source) {
        SourceRules.Rule rule = SourceRules.ALL.stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElseThrow();
        List<Violation> found = new ArrayList<>();
        JavaSources.parse(Map.of(file, source)).forEach(unit -> found.addAll(Boundaries.find(rule, unit, CONTEXT)));
        return found.stream().map(Violation::line).distinct().sorted().toList();
    }

    private static final Map<String, Integer> LINE_OF_RULE = Map.of("b-os-branching", 3, "c-getenv", 4, "c-getproperty", 5, "g-string-switch", 6, "g-string-equals", 7, "i-path-string", 8);

    @Test
    void theNarrowedRulesStillCountTheSkillsCodeThatDecides() {
        assertEquals(Boundaries.NARROWED, LINE_OF_RULE.keySet(), "the fixture has a hit for every narrowed rule");
        LINE_OF_RULE.forEach((id, line) -> assertEquals(List.of(line), counted(id, SKILLS_FILE, ONE_OF_EACH), id + " in server/skills is business code"));
        assertEquals(List.of(4), counted("c-getenv", "sennard-server/src/main/java/io/github/jaeseongs95/sennard/server/workflow/Fixture.java", ONE_OF_EACH), "System.getenv in server/workflow is counted");
    }

    @Test
    void aHostHookPackageIsLeftOutOfTheKindAndEnvironmentAndPathRulesButNotOfTheOperatingSystemRule() {
        LINE_OF_RULE.forEach((id, line) -> assertEquals(id.equals("b-os-branching") ? List.of(line) : List.of(), counted(id, HOST_FILE, ONE_OF_EACH),
                id + " in server/hook/host: a flag that decides by operating system is counted there too, the rest is host input conversion"));
        assertEquals(List.of(), counted("c-getenv", "sennard-server/src/main/java/io/github/jaeseongs95/sennard/server/hook/host/deeper/Fixture.java", ONE_OF_EACH), "a sub-package of a boundary is in it");
    }

    @Test
    void theInstallerAndTheBuildToolAreLeftOutOfAllSixRules() {
        LINE_OF_RULE.keySet().forEach(id -> {
            assertEquals(List.of(), counted(id, INSTALL_FILE, ONE_OF_EACH), id + " in the installer");
            assertEquals(List.of(), counted(id, "sennard-repo-tools/src/main/java/io/github/jaeseongs95/sennard/repotools/Fixture.java", ONE_OF_EACH), id + " in the build tool");
        });
    }

    @Test
    void aBoundaryIsExactlyItsPackageOrTypeAndNeverAnotherWithTheSameBeginning() {
        String hook = "sennard-server/src/main/java/io/github/jaeseongs95/sennard/server/hook/";
        String personal = "sennard-personal/src/main/java/io/github/jaeseongs95/sennard/personal/";
        // No type stands on the list now (the one that did handed the environment to the embedded database's preparation), so the exact-type rule is read from the record.
        Boundaries.Boundary type = new Boundaries.Boundary(personal + "Type.java", true, "a type", Set.of("c-getenv"));
        assertTrue(type.covers("c-getenv", personal + "Type.java"), "the one type named");
        assertFalse(type.covers("c-getenv", personal + "TypeHelper.java"), "another type whose name begins with it");
        assertEquals(List.of(4), counted("c-getenv", hook + "HookController.java", ONE_OF_EACH), "the hook package itself is not a boundary, only its host sub-package");
        assertEquals(List.of(4), counted("c-getenv", hook + "hostile/Fixture.java", ONE_OF_EACH), "a package whose name begins with the boundary's");
        assertEquals(List.of(4), counted("c-getenv", "sennard-store-pg/src/main/java/io/github/jaeseongs95/sennard/service/store/pg/StorePreparation.java", ONE_OF_EACH), "not clearly a boundary: stays counted");
        assertEquals(List.of(3), counted("b-os-branching", "sennard-core/src/main/java/io/github/jaeseongs95/sennard/service/identity/HostWatch.java", ONE_OF_EACH), "not clearly a boundary: stays counted");
    }

    // ------------------------------------------------------------------------------------------------------------- script executables

    @TempDir
    Path repository;

    private void write(String path, String content) throws IOException {
        Path file = repository.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static List<String> places(List<Violation> violations) {
        return violations.stream().sorted(java.util.Comparator.comparing(Violation::file).thenComparingInt(Violation::line)).map(Violation::place).toList();
    }

    @Test
    void aScriptByExtensionOrByShebangOutsideTheAllowListIsFound() throws IOException {
        write("tools/x.sh", "echo hi\n");
        write("bin/run", "#!/bin/sh\necho hi\n");
        write("win/Setup.PS1", "Write-Host hi\n");
        write("web/app.js", "alert(1)\n");
        write("ci/build.bat", "@echo off\n");
        write("java/other/gradlew.bat", "@echo off\n");
        write("a/b.py", "print(1)\n");
        assertEquals(List.of("a/b.py:1", "bin/run:1", "ci/build.bat:1", "java/other/gradlew.bat:1", "tools/x.sh:1", "web/app.js:1", "win/Setup.PS1:1"), places(RepositoryRules.scriptExecutables(repository)));
    }

    @Test
    void theGradleWrapperDocumentsPagesWithScriptTagsAndBuildFoldersAreLeftAlone() throws IOException {
        write("java/gradlew", "#!/bin/sh\nexec java\n");
        write("java/gradlew.bat", "@echo off\n");
        write("README.md", "# Readme\n#!not at the start of the file\n");
        write("docs/notes.txt", "see #! lines\n");
        write("java/sennard-personal/src/main/resources/admin/index.html", "<html><script>alert(1)</script></html>\n");
        write("java/sennard-x/build/tmp/x.sh", "#!/bin/sh\n");
        write("java/.gradle/y.sh", "#!/bin/sh\n");
        write(".git/hooks/pre-commit", "#!/bin/sh\n");
        write("web/node_modules/z/index.js", "x\n");
        write("small", "#");
        assertEquals(List.of(), RepositoryRules.scriptExecutables(repository));
    }

    // ------------------------------------------------------------------------------------------------------------------ hook commands

    @Test
    void aHookHandlerOfAnyTypeInAPackageIsFound() {
        List<Violation> found = RepositoryRules.hookCommands("hooks.json", """
                {"hooks": {"SessionStart": [{"hooks": [
                  {
                    "type": "command",
                    "command": "fetcher --quiet http://127.0.0.1:1234/hooks/v1/claude/SessionStart"
                  },
                  {
                    "type": "command",
                    "command": "java",
                    "args": ["-cp", "client.jar", "io.github.jaeseongs95.sennard.hookclient.HookClientMain"]
                  },
                  {"type": "http", "url": "http://127.0.0.1:1234/hooks/v1/claude/Stop"},
                  {"type": "mcp_tool", "tool": "hook", "server": "sennard"}
                ]}]}}
                """);
        assertEquals(List.of("hooks.json:2", "hooks.json:6", "hooks.json:11", "hooks.json:12"), places(found),
                "a command of any program (the Sennard hook client too: its Java and jar are the installation's), an HTTP hook and an MCP tool hook");
    }

    @Test
    void anEmptyDefinitionIsLeftAlone() {
        assertEquals(List.of(), RepositoryRules.hookCommands("hooks/hooks.json", "{\n  \"hooks\": {}\n}\n"));
        assertEquals(List.of(), RepositoryRules.hookCommands("hooks.json", "{}\n"));
        assertEquals(List.of(), RepositoryRules.mcpServers(".mcp.json", "{\n  \"mcpServers\": {}\n}\n"));
        assertEquals(List.of(), RepositoryRules.mcpServers("gemini-extension.json", "{\"name\": \"sennard\", \"version\": \"6.0.0\"}"));
    }

    @Test
    void anMcpServerInAPackageIsFoundWhereverTheFileHoldsIt() {
        List<Violation> found = RepositoryRules.mcpServers(".mcp.json", """
                {"mcpServers": {
                  "sennard": {
                    "command": "java",
                    "args": ["-cp", "client.jar", "Relay"],
                    "env": {"A": "b"}
                  },
                  "other": {"url": "http://127.0.0.1:1234/mcp"}
                }}
                """);
        assertEquals(List.of(".mcp.json:2", ".mcp.json:7"), places(found), "each server once, and not the objects inside a server");
    }

    @Test
    void theDefinitionsOfAPackageAreReadFromTheirPlacesAndAMissingOneHasNothingToCount() throws IOException {
        write("hooks.json", "{\"g\": {\"Stop\": [{\"type\": \"command\", \"command\": \"fetcher\"}]}}");
        write("claude-plugin/hooks/hooks.json", "{\"hooks\": {}}");
        write(".mcp.json", "{\"mcpServers\": {\"sennard\": {\"command\": \"java\"}}}");
        write("claude-overlay/.claude-plugin/plugin.json", "{\"name\": \"sennard\", \"mcpServers\": {\"sennard\": {\"command\": \"java\"}}}");
        write("claude-plugin/.mcp.json", "{\"mcpServers\": {}}");
        write("locales/en/hooks.json", "{\"hooks\": [{\"type\": \"command\", \"command\": \"fetcher\"}]}");
        assertEquals(List.of(".mcp.json:1", "claude-overlay/.claude-plugin/plugin.json:1", "hooks.json:1"), places(RepositoryRules.hookCommands(repository)));
    }

    // ------------------------------------------------------------------------------------------------------------------------- CI runs

    private static final String WORKFLOW = """
            permissions:
              contents: read
            jobs:
              build:
                steps:
                  - name: A
                    run: ./gradlew test
                  - name: B
                    run: |
                      ./gradlew a
                      tar -xzf x.tgz
                  - name: C
                    run: fetcher --quiet http://x
                  - name: D
                    run: |
                      # a comment

                      ./gradlew b \\
                        --info
                      ./gradlew c
                  - name: E
                    env:
                      run: tar
                    run: ./gradlew :m:test
            """;

    @Test
    void aRunStepWithALineThatIsNotGradlewIsFoundOncePerStep() {
        List<Violation> found = RepositoryRules.ciRuns("ci.yml", WORKFLOW);
        assertEquals(List.of("ci.yml:8", "ci.yml:12"), places(found));
        assertEquals(List.of("tar -xzf x.tgz", "fetcher --quiet http://x"), found.stream().sorted(java.util.Comparator.comparingInt(Violation::line)).map(Violation::text).toList());
    }

    @Test
    void gradlewLinesCommentsBlankLinesContinuationsAndOtherRunKeysAreLeftAlone() {
        assertEquals(List.of(), RepositoryRules.ciRuns("ci.yml", """
                permissions:
                  contents: read
                jobs:
                  build:
                    steps:
                      - uses: actions/checkout@v4
                      - run: ./gradlew --no-daemon test --fail-fast
                      - run: |
                          # comment first

                          ./gradlew a
                          ./gradlew b \\
                            --info
                      - name: with
                        with:
                          run: tar
                """));
    }

    @Test
    void theWorkflowsAreReadFromTheWorkflowFolderOnly() throws IOException {
        write(".github/workflows/ci.yml", WORKFLOW);
        write(".github/workflows/notes.txt", "run: tar\n");
        write("other/ci.yml", WORKFLOW);
        assertEquals(List.of(".github/workflows/ci.yml:8", ".github/workflows/ci.yml:12"), places(RepositoryRules.ciRuns(repository)));
        assertEquals(List.of(), RepositoryRules.ciRuns(repository.resolve("other")), "no workflow folder, nothing to count");
    }

    /** A workflow whose only job holds a write token, with the given steps (indented by six spaces, so the first step is on line 8). */
    private static String uploadJob(String steps) {
        return "permissions:\n  contents: read\njobs:\n  attach:\n    permissions:\n      contents: write\n    steps:\n" + steps.indent(6);
    }

    /** The shape of the release workflows: jobs that build and check hold a read token and call only ./gradlew, the job that uploads holds the write token and runs no repository code. */
    @Test
    void theShapeOfTheReleaseWorkflowsIsAccepted() {
        assertEquals(List.of(), RepositoryRules.ciRuns("server-package.yml", """
                permissions:
                  contents: read
                jobs:
                  package:
                    steps:
                      - uses: actions/checkout@v7
                        with:
                          persist-credentials: false
                      - uses: actions/setup-java@v5
                      - name: Build the archive
                        shell: bash
                        working-directory: java
                        run: ./gradlew --no-daemon :sennard-personal:releaseArchive
                      - uses: actions/upload-artifact@v7
                  check:
                    needs: package
                    steps:
                      - uses: actions/checkout@v7
                      - uses: actions/download-artifact@v8
                      - run: ./gradlew :sennard-repo-tools:checkReleaseChecksums
                  attach:
                    needs: check
                    permissions:
                      contents: write
                    steps:
                      - name: Download every platform's archive
                        uses: actions/download-artifact@9000827ccba6bdab643e8b6fd33ac0654aef8333 # v8.0.2
                        with:
                          pattern: sennard-server-*
                          path: assets
                      - name: Attach the archives and checksums
                        env:
                          GH_TOKEN: ${{ github.token }}
                        run: gh release upload "$TAG" assets/* --clobber --repo "$GITHUB_REPOSITORY"
                """));
    }

    @Test
    void aWriteJobThatDownloadsArtifactsAndRunsOnlyGhReleaseUploadIsAccepted() {
        assertEquals(List.of(), RepositoryRules.ciRuns("p.yml", uploadJob("""
                - uses: actions/download-artifact@v8
                - run: |
                    # the files the build job handed over

                    gh release upload "$TAG" assets/* \\
                      --clobber --repo "$GITHUB_REPOSITORY"
                    gh release upload "$TAG" extra.zip
                """)));
    }

    @Test
    void aWriteJobThatChecksOutRunsGradlewARepositoryScriptOrAnotherActionIsFound() {
        record Case(String step, String text) {
        }
        List<Case> cases = List.of(
                new Case("- uses: actions/checkout@v7", "write job attach: uses actions/checkout@v7"),
                new Case("- uses: actions/setup-java@v5", "write job attach: uses actions/setup-java@v5"),
                new Case("- uses: softprops/action-gh-release@v2", "write job attach: uses softprops/action-gh-release@v2"),
                new Case("- uses: ./.github/actions/attach", "write job attach: uses ./.github/actions/attach"),
                new Case("- run: ./gradlew :sennard-repo-tools:releaseAttach", "write job attach: ./gradlew :sennard-repo-tools:releaseAttach"),
                new Case("- run: ./scripts/attach", "write job attach: ./scripts/attach"),
                new Case("- run: |\n    gh release upload v1 a.zip\n    sh attach.sh", "write job attach: sh attach.sh"),
                new Case("- run: gh release create v1", "write job attach: gh release create v1"),
                new Case("- run: gh release uploader v1", "write job attach: gh release uploader v1"),
                new Case("- run: gh release upload v1 a.zip; ./attach", "write job attach: gh release upload v1 a.zip; ./attach"),
                new Case("- run: gh release upload v1 a.zip && curl http://x", "write job attach: gh release upload v1 a.zip && curl http://x"),
                new Case("- run: gh release upload v1 a.zip | tee log", "write job attach: gh release upload v1 a.zip | tee log"),
                new Case("- run: gh release upload v1 a.zip > log", "write job attach: gh release upload v1 a.zip > log"),
                new Case("- run: gh release upload v1 $(cat names)", "write job attach: gh release upload v1 $(cat names)"),
                new Case("- run: gh release upload v1 `cat names`", "write job attach: gh release upload v1 `cat names`"));
        for (Case each : cases) {
            List<Violation> found = RepositoryRules.ciRuns("p.yml", uploadJob(each.step()));
            assertEquals(List.of("p.yml:8"), places(found), each.step());
            assertEquals(each.text(), found.get(0).text(), each.step());
        }
    }

    @Test
    void aWriteJobThatCallsAReusableWorkflowIsFound() {
        List<Violation> found = RepositoryRules.ciRuns("p.yml", """
                permissions:
                  contents: read
                jobs:
                  attach:
                    permissions:
                      contents: write
                    uses: ./.github/workflows/attach.yml
                """);
        assertEquals(List.of("write job attach: uses ./.github/workflows/attach.yml"), found.stream().map(Violation::text).toList());
    }

    /** Whether a job's token can write comes from the job's {@code permissions}, else the workflow's, else the repository's default (which may write, so it counts as write). */
    @Test
    void theTokenOfAJobIsReadOnlyOnlyWhenThePermissionsSayIt() {
        record Case(String workflow, String job, boolean writes) {
        }
        String read = "permissions:\n  contents: read\n";
        String write = "permissions:\n  contents: write\n";
        List<Case> cases = List.of(
                new Case(read, "", false),
                new Case(write, "", true),
                new Case("", "", true),
                new Case(read, "    permissions:\n      contents: write\n", true),
                new Case(read, "    permissions:\n      contents: read\n      packages: write\n", true),
                new Case(write, "    permissions:\n      contents: read\n", false),
                new Case(write, "    permissions: {}\n", false),
                new Case("", "    permissions: read-all\n", false),
                new Case(read, "    permissions: write-all\n", true),
                new Case("permissions: write-all\n", "", true),
                new Case("permissions: read-all\n", "", false),
                new Case("", "    permissions:\n      contents: read\n", false));
        for (Case each : cases) {
            String workflow = each.workflow() + "jobs:\n  work:\n" + each.job() + "    steps:\n      - run: ./gradlew test\n      - uses: actions/checkout@v7\n";
            List<Violation> found = RepositoryRules.ciRuns("p.yml", workflow);
            // The two steps are fine for a job that cannot write; in one that can, neither is allowed.
            assertEquals(each.writes() ? List.of("write job work: ./gradlew test", "write job work: uses actions/checkout@v7") : List.of(), found.stream().map(Violation::text).toList(), workflow);
        }
    }

    @Test
    void aReadOnlyJobMayUseAnyActionButRunsOnlyGradlew() {
        List<Violation> found = RepositoryRules.ciRuns("p.yml", """
                permissions:
                  contents: read
                jobs:
                  check:
                    steps:
                      - uses: actions/checkout@v7
                      - run: ./gradlew test
                      - run: gh release upload v1 a.zip
                """);
        assertEquals(List.of("p.yml:8"), places(found));
        assertEquals("gh release upload v1 a.zip", found.get(0).text());
    }

    // ------------------------------------------------------------------------------------------------------------------------------ a

    /** The compiled classes and their sources lie in this test's own temporary folder, which JUnit deletes after the test (@TempDir). */
    private Path compile(Map<String, String> sources) throws IOException {
        Path classes = Files.createTempDirectory(repository, "arch-fixture-");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            List<JavaFileObject> objects = new ArrayList<>();
            Path root = Files.createTempDirectory(repository, "arch-fixture-src-");
            for (Map.Entry<String, String> entry : sources.entrySet()) {
                Path file = root.resolve(entry.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
                files.getJavaFileObjects(file).forEach(objects::add);
            }
            boolean ok = compiler.getTask(null, files, null, List.of("-d", classes.toString(), "-proc:none"), null, objects).call();
            assertEquals(true, ok, "the fixture sources must compile");
        }
        return classes;
    }

    @Test
    void twoPackagesThatUseEachOtherAreACycleAndOneWayUseIsNot() throws IOException {
        Path cyclic = compile(Map.of(
                "io/github/jaeseongs95/sennard/x/X.java", "package io.github.jaeseongs95.sennard.x; public class X { public io.github.jaeseongs95.sennard.y.Y y; }",
                "io/github/jaeseongs95/sennard/y/Y.java", "package io.github.jaeseongs95.sennard.y; public class Y { public io.github.jaeseongs95.sennard.x.X x; }"));
        List<Violation> cycles = PackageCycles.directCycles("a", List.of(cyclic));
        assertEquals(1, cycles.size());
        assertEquals(true, cycles.get(0).text().startsWith("io.github.jaeseongs95.sennard.x <-> io.github.jaeseongs95.sennard.y"), cycles.get(0).text());
        Path oneWay = compile(Map.of(
                "io/github/jaeseongs95/sennard/x/X.java", "package io.github.jaeseongs95.sennard.x; public class X { public io.github.jaeseongs95.sennard.y.Y y; }",
                "io/github/jaeseongs95/sennard/y/Y.java", "package io.github.jaeseongs95.sennard.y; public class Y { }"));
        assertEquals(List.of(), PackageCycles.directCycles("a", List.of(oneWay)));
    }
}
