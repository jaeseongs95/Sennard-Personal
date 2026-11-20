// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.glossary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.glossary.GlossaryCatalog;
import io.github.jaeseongs95.sennard.domain.glossary.GlossaryContent;
import io.github.jaeseongs95.sennard.domain.glossary.GlossaryEntry;
import io.github.jaeseongs95.sennard.domain.glossary.GlossaryEntryStatus;
import io.github.jaeseongs95.sennard.domain.glossary.GlossaryForm;
import io.github.jaeseongs95.sennard.domain.glossary.GlossaryFormKind;
import io.github.jaeseongs95.sennard.domain.glossary.GlossaryPolicy;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.server.glossary.KoreanProseGlossary.LookupRequest;
import io.github.jaeseongs95.sennard.server.glossary.KoreanProseGlossary.LookupResult;
import io.github.jaeseongs95.sennard.server.glossary.KoreanProseGlossary.Match;
import io.github.jaeseongs95.sennard.server.glossary.KoreanProseGlossary.Status;
import io.github.jaeseongs95.sennard.server.glossary.KoreanProseGlossary.Warning;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Korean prose glossary without the tool's door. Its terms are the glossary's tables on the PostgreSQL database the test run names, made by the migrations and read through the catalog the server uses
 * (the answers of the real data, the digests derived from the rows by SQL of the test's own); the rules a glossary must hold are cases over an in-memory catalog, since the tables'
 * constraints (CHECK, keys) keep a database from holding most of what a rule refuses. Every status a lookup ends in and the refusal of a digest that is not the text's are here too.
 */
@Tag("business-db")
class KoreanProseGlossaryTest {
    private static final String PROSE = "프로젝트를 위한 문서를 보여주기만 하고 끝낸다.";
    private static final String HEX_DIGEST = "[a-f0-9]{64}";
    private static DataSource database;
    private static KoreanProseGlossary seeded;
    /** The content digest of the rows, made by the test from SQL. */
    private static String contentDigest;

    @BeforeAll
    static void load() {
        database = GlossaryDatabase.open();
        contentDigest = GlossaryDatabase.contentDigest(database, Set.of());
        seeded = new KoreanProseGlossary(GlossaryJson.MAPPER, new GlossaryLoader(new JdbcGlossaryCatalog(database), GlossaryJson.MAPPER));
    }

    // ------------------------------------------------------------------------------------------------------------------------- helpers

    private static String digest(String text) {
        return Digests.sha256Hex(text);
    }

    private static LookupResult lookup(KoreanProseGlossary glossary, String text) {
        return glossary.lookup(new LookupRequest("1.0.0", text, digest(text)));
    }

    private static JsonNode json(Object value) {
        return GlossaryJson.tree(value);
    }

    private static GlossaryForm canonical(String form) {
        return new GlossaryForm(form, GlossaryFormKind.CANONICAL);
    }

    private static GlossaryForm alias(String form) {
        return new GlossaryForm(form, GlossaryFormKind.ALIAS);
    }

    private static GlossaryEntry entry(String entryId, String canonical, GlossaryPolicy policy, int priority, boolean active, GlossaryForm... forms) {
        return new GlossaryEntry(entryId, canonical, policy, "https://example.test/" + entryId, priority, active ? GlossaryEntryStatus.ACTIVE : GlossaryEntryStatus.RETIRED, List.of(forms));
    }

    /** A glossary over entries held in memory: the rules of the glossary apply whatever the catalog is. */
    private static KoreanProseGlossary over(GlossaryEntry... entries) {
        return new KoreanProseGlossary(GlossaryJson.MAPPER, new GlossaryLoader(() -> new GlossaryContent("korean-prose-core", "9.9.9", List.of(entries)), GlossaryJson.MAPPER));
    }

    /** What a match is on the wire, written the way the contract names its members. */
    private static ObjectNode wire(int start, int end, String entryId, String policy, String canonical, int priority) {
        return GlossaryJson.object("start", start, "end", end, "entryId", entryId, "policy", policy, "canonicalForm", canonical, "priority", priority);
    }

    private static ObjectNode glossaryOf() {
        return GlossaryJson.object("id", "korean-prose-core", "version", "1.3.0", "contentDigest", contentDigest);
    }

    private static String matchSetDigest(String text, List<ObjectNode> matches) {
        ObjectNode digested = GlossaryJson.object("schemaVersion", "1.0.0", "sourceDigest", digest(text), "glossary", glossaryOf(), "matches", GlossaryJson.array(matches));
        return Digests.sha256Hex(CanonicalJson.of(digested));
    }

    // ------------------------------------------------------------------------------------------------------------------------- the tables on H2

    @Test
    void theScriptsMakeTheTermsOfVersion130() {
        JdbcClient jdbc = JdbcClient.create(database);
        assertEquals("1.3.0", jdbc.sql("SELECT version FROM sennard.glossary_metadata WHERE glossary_id = 'korean-prose-core'").query(String.class).single());
        assertEquals(265, jdbc.sql("SELECT count(*) FROM sennard.glossary_entries").query(Integer.class).single());
        assertEquals(270, jdbc.sql("SELECT count(*) FROM sennard.glossary_forms").query(Integer.class).single());
        assertEquals(Map.of("PROTECT", 177, "ALLOW", 68, "PREFER", 7, "AVOID", 13),
                jdbc.sql("SELECT policy, count(*) FROM sennard.glossary_entries GROUP BY policy").query((rows, number) -> Map.entry(rows.getString(1), rows.getInt(2))).list().stream()
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        assertEquals(1, jdbc.sql("SELECT count(*) FROM sennard.glossary_entries WHERE status = 'RETIRED'").query(Integer.class).single());
        assertEquals(22, jdbc.sql("SELECT count(*) FROM sennard.glossary_entries WHERE entry_id IN ('java', 'jdk', 'jdbc', 'spring-boot', 'spring-framework', 'gradle', 'junit', 'postgresql', "
                + "'claude-code', 'gemini-cli', 'antigravity', 'logback', 'slf4j', 'jackson', 'flyway', 'content-ko', 'shell-ko', 'concept-ko', 'message-ko', 'directory-ko', 'adapter-ko', 'browser-ko')")
                .query(Integer.class).single());
    }

    @Test
    void theTermsOfVersion121AreStillTheSameBytesAsTheyWere() {
        // The 243 terms of 1.2.1 were a seed file in the jar; they are rows now. Left out of the 22 terms 1.3.0 added, the rows make the digest the golden answer had.
        assertEquals(GlossaryDatabase.DIGEST_OF_1_2_1, GlossaryDatabase.contentDigest(database, GlossaryDatabase.ADDED_IN_1_3_0));
    }

    @Test
    void theDataScriptCanRunAgainAndLeavesTheSameTerms() throws Exception {
        // The migrations ran it once (the database of this class); the file is run over them again, and the terms are the same bytes and the same count.
        try (java.sql.Connection connection = database.getConnection()) {
            ResourceDatabasePopulator data = new ResourceDatabasePopulator(false, false, "UTF-8", new FileSystemResource(GlossaryDatabase.dataScript()));
            String before = GlossaryDatabase.contentDigest(database, Set.of());
            data.populate(connection);
            assertEquals(before, GlossaryDatabase.contentDigest(database, Set.of()));
            assertEquals(contentDigest, before);
            assertEquals(265, JdbcClient.create(database).sql("SELECT count(*) FROM sennard.glossary_entries").query(Integer.class).single());
        }
    }

    @Test
    void theTablesRefuseWhatTheirConstraintsListNotAPolicyNotAStatusNotAPriorityNotAnOrphanForm() throws Exception {
        // One connection runs all of it, and each refusal has its valid twin accepted on that connection, so a refusal is the constraint's and not the connection's. The statements
        // commit by themselves (a refused one would end a transaction of PostgreSQL), and the two valid twins are removed at the end, so the other cases see the terms as the migrations made them.
        try (java.sql.Connection connection = database.getConnection()) {
            JdbcClient jdbc = JdbcClient.create(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
            String entry = "INSERT INTO sennard.glossary_entries (entry_id, canonical_form, policy, source_ref, priority, status) VALUES ";
            String form = "INSERT INTO sennard.glossary_forms (owner_entry_id, ordinal, form_text, form_kind) VALUES ";
            assertEquals(1, jdbc.sql(entry + "('twin', 'Twin', 'PROTECT', 'https://example.test/x', 1, 'ACTIVE')").update(), "the valid entry is accepted");
            assertEquals(1, jdbc.sql(entry + "('twin-retired', 'Twin', 'PROTECT', 'https://example.test/x', 1000, 'RETIRED')").update(), "the other status and the top of the priority range");
            assertEquals(1, jdbc.sql(form + "('twin', 1, 'Twin', 'CANONICAL')").update(), "the valid form is accepted");
            assertThrows(Exception.class, () -> jdbc.sql(entry + "('x', 'X', 'KEEP', 'https://example.test/x', 1, 'ACTIVE')").update());
            assertThrows(Exception.class, () -> jdbc.sql(entry + "('x', 'X', 'protect', 'https://example.test/x', 1, 'ACTIVE')").update(), "the column holds the constant's name, not the JSON word");
            assertThrows(Exception.class, () -> jdbc.sql(entry + "('x', 'X', 'PROTECT', 'https://example.test/x', 1, 'GONE')").update(), "a status that is not one of the two");
            assertThrows(Exception.class, () -> jdbc.sql(entry + "('x', 'X', 'PROTECT', 'https://example.test/x', 1, 'active')").update(), "the status is the constant's name");
            assertThrows(Exception.class, () -> jdbc.sql(entry + "('x', 'X', 'PROTECT', 'https://example.test/x', 1001, 'ACTIVE')").update());
            assertThrows(Exception.class, () -> jdbc.sql(entry + "('x', 'X', 'PROTECT', 'https://example.test/x', -1, 'ACTIVE')").update());
            assertThrows(Exception.class, () -> jdbc.sql(entry + "('codex', 'Codex', 'PROTECT', 'https://example.test/x', 1, 'ACTIVE')").update(), "a repeated entry id");
            assertThrows(Exception.class, () -> jdbc.sql(form + "('no-such-entry', 1, 'X', 'CANONICAL')").update(), "a form of no entry");
            assertThrows(Exception.class, () -> jdbc.sql(form + "('codex', 2, 'X', 'primary')").update());
            assertThrows(Exception.class, () -> jdbc.sql(form + "('codex', 1, 'Y', 'ALIAS')").update(), "a repeated place");
            assertThrows(Exception.class, () -> jdbc.sql(form + "('codex', 2, 'Codex', 'ALIAS')").update(), "a repeated spelling");
            assertEquals(267, jdbc.sql("SELECT count(*) FROM sennard.glossary_entries").query(Integer.class).single(), "the 265 and the two valid twins; no refused row got in");
            jdbc.sql("DELETE FROM sennard.glossary_forms WHERE owner_entry_id = 'twin'").update();
            jdbc.sql("DELETE FROM sennard.glossary_entries WHERE entry_id IN ('twin', 'twin-retired')").update();
            assertEquals(265, jdbc.sql("SELECT count(*) FROM sennard.glossary_entries").query(Integer.class).single(), "the twins are gone again");
        }
    }

    @Test
    void theTablesScriptIsAnsiOnlyWithNoRoleAndNoOtherSchema() throws Exception {
        String tables = java.nio.file.Files.readString(GlossaryDatabase.tablesScript(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        for (String word : List.of("collate", "jsonb", "serial", " text", "bytea", "timestamptz", "on conflict", "if not exists", "grant", "row level security", "create policy", "create function")) {
            assertFalse(tables.contains(word), "V13 holds " + word);
        }
        assertTrue(tables.contains("varchar") && tables.contains("integer"));
        assertFalse(tables.contains("boolean"), "no truth-value column: an enum name in a VARCHAR with a CHECK");
        assertFalse(java.nio.file.Files.readString(GlossaryDatabase.dataScript(), StandardCharsets.UTF_8).matches("(?s).*\\b(TRUE|FALSE)\\b.*"), "the data script writes no truth value");
    }

    // ------------------------------------------------------------------------------------------------------------------------- the terms of the tables

    @Test
    void aTextWithNoTermAnswersNoMatchWithTheDigestsOfTheTables() {
        LookupResult result = lookup(seeded, PROSE);
        ObjectNode expected = GlossaryJson.object("schemaVersion", "1.0.0", "status", "no-match", "sourceDigest", digest(PROSE), "glossary", glossaryOf(), "matches", GlossaryJson.array(List.of()),
                "matchSetDigest", matchSetDigest(PROSE, List.of()), "warnings", GlossaryJson.array(List.of()));
        assertEquals(expected, json(result));
        assertTrue(result.glossary().contentDigest().matches(HEX_DIGEST));
    }

    @Test
    void theLoaderMakesTheSameDigestAsTheOneMadeFromTheRowsBySql() {
        assertEquals(contentDigest, lookup(seeded, PROSE).glossary().contentDigest());
    }

    @Test
    void everyFormOfAnActiveEntryIsReportedWithItsPolicyAtItsPlace() {
        String text = "Codex와 ChatGPT를 쓴다. 레포지토리와 알고 리즘.";
        LookupResult result = lookup(seeded, text);
        assertEquals(Status.MATCHED, result.status());
        List<ObjectNode> expected = List.of(wire(text.indexOf("Codex"), text.indexOf("Codex") + 5, "codex", "protect", "Codex", 100),
                wire(text.indexOf("ChatGPT"), text.indexOf("ChatGPT") + 7, "chatgpt", "protect", "ChatGPT", 100),
                wire(text.indexOf("레포지토리"), text.indexOf("레포지토리") + 5, "repository-ko", "prefer", "리포지토리", 50),
                wire(text.indexOf("알고 리즘"), text.indexOf("알고 리즘") + 5, "algorithm-spacing-ko", "avoid", "알고리즘", 60));
        assertEquals(GlossaryJson.array(expected), json(result.matches()));
        // The match set's digest is the canonical form of the contract's members, not of anything the implementation chooses.
        assertEquals(matchSetDigest(text, expected), result.matchSetDigest());
        assertTrue(result.matchSetDigest().matches(HEX_DIGEST));
        assertEquals(List.of(), result.warnings());
        assertEquals(contentDigest, result.glossary().contentDigest());
    }

    @Test
    void theTermsOfVersion130AreFoundInARealLookupProtectedPreferredAndAvoided() {
        String text = "Java와 Spring Boot, Gradle을 쓴다. 컨텐츠와 쉘, 메세지, 디렉토리를 점검한다.";
        LookupResult result = lookup(seeded, text);
        assertEquals(Status.MATCHED, result.status());
        List<ObjectNode> expected = new ArrayList<>();
        expected.add(wire(text.indexOf("Java"), text.indexOf("Java") + 4, "java", "protect", "Java", 100));
        expected.add(wire(text.indexOf("Spring Boot"), text.indexOf("Spring Boot") + 11, "spring-boot", "protect", "Spring Boot", 110));
        expected.add(wire(text.indexOf("Gradle"), text.indexOf("Gradle") + 6, "gradle", "protect", "Gradle", 100));
        expected.add(wire(text.indexOf("컨텐츠"), text.indexOf("컨텐츠") + 3, "content-ko", "prefer", "콘텐츠", 50));
        expected.add(wire(text.indexOf("쉘"), text.indexOf("쉘") + 1, "shell-ko", "prefer", "셸", 50));
        expected.add(wire(text.indexOf("메세지"), text.indexOf("메세지") + 3, "message-ko", "avoid", "메시지", 60));
        expected.add(wire(text.indexOf("디렉토리"), text.indexOf("디렉토리") + 4, "directory-ko", "avoid", "디렉터리", 60));
        assertEquals(GlossaryJson.array(expected), json(result.matches()));
    }

    @Test
    void theRightSpellingsOfTheNewAvoidAndPreferTermsAreNotReported() {
        LookupResult result = lookup(seeded, "콘텐츠와 셸, 콘셉트, 메시지, 디렉터리, 어댑터, 브라우저.");
        assertEquals(List.of(), result.matches().stream().filter(match -> match.policy() == GlossaryPolicy.AVOID || match.policy() == GlossaryPolicy.PREFER).toList());
    }

    @Test
    void anAllowedEntryReportsItsCanonicalFormAndItsAlias() {
        String text = "커맨드 라인으로 명령줄을 쓴다.";
        LookupResult result = lookup(seeded, text);
        assertEquals(GlossaryJson.array(List.of(wire(0, 6, "command-line-ko", "allow", "명령줄", 25), wire(text.indexOf("명령줄"), text.indexOf("명령줄") + 3, "command-line-ko", "allow", "명령줄", 25))),
                json(result.matches()));
    }

    @Test
    void anInactiveEntryIsNeverReported() {
        LookupResult result = lookup(seeded, "완료 영수증을 낸다.");
        assertEquals(Status.NO_MATCH, result.status());
        assertEquals(List.of(), result.matches());
    }

    // ------------------------------------------------------------------------------------------------------------------------- the order of matches and the limits

    @Test
    void matchesComeInOrderOfStartThenLongerFirstAndOverlapsAreAllReported() {
        KoreanProseGlossary glossary = over(entry("ab", "AB", GlossaryPolicy.PROTECT, 10, true, canonical("AB")), entry("a", "A", GlossaryPolicy.PROTECT, 20, true, canonical("A")),
                entry("aa", "AA", GlossaryPolicy.PROTECT, 5, true, canonical("AA")));
        assertEquals(GlossaryJson.array(List.of(wire(0, 2, "ab", "protect", "AB", 10), wire(0, 1, "a", "protect", "A", 20), wire(2, 3, "a", "protect", "A", 20))), json(lookup(glossary, "ABA").matches()));
        assertEquals(GlossaryJson.array(List.of(wire(0, 2, "aa", "protect", "AA", 5), wire(0, 1, "a", "protect", "A", 20), wire(1, 3, "aa", "protect", "AA", 5), wire(1, 2, "a", "protect", "A", 20),
                wire(2, 3, "a", "protect", "A", 20))), json(lookup(glossary, "AAA").matches()));
    }

    @Test
    void moreThanTheMatchLimitIsLimitExceededAndNamesTheGlossary() {
        LookupResult atLimit = lookup(seeded, "Codex ".repeat(256));
        assertEquals(Status.MATCHED, atLimit.status());
        assertEquals(256, atLimit.matches().size());
        assertTrue(atLimit.matchSetDigest().matches(HEX_DIGEST));

        LookupResult over = lookup(seeded, "Codex ".repeat(257));
        assertEquals(Status.LIMIT_EXCEEDED, over.status());
        assertEquals(List.of(), over.matches());
        assertNull(over.matchSetDigest());
        assertEquals(List.of(Warning.GLOSSARY_MATCH_LIMIT_EXCEEDED), over.warnings());
        assertEquals(contentDigest, over.glossary().contentDigest());
        assertEquals(digest("Codex ".repeat(257)), over.sourceDigest());
    }

    @Test
    void aTextOverTheLengthLimitIsAnsweredWithoutTheGlossary() {
        assertEquals(Status.NO_MATCH, lookup(seeded, "가".repeat(200_000)).status());
        LookupResult over = lookup(seeded, "가".repeat(200_001));
        assertEquals(Status.LIMIT_EXCEEDED, over.status());
        assertNull(over.glossary());
        assertNull(over.matchSetDigest());
        assertEquals(List.of(Warning.GLOSSARY_MATCH_LIMIT_EXCEEDED), over.warnings());
    }

    @Test
    void aTextNotInNormalizationFormCIsNotLookedUp() {
        String decomposed = "가"; // the letter 가 as two jamo
        LookupResult result = lookup(seeded, decomposed);
        assertEquals(Status.UNSUPPORTED_NORMALIZATION, result.status());
        assertNull(result.glossary());
        assertNull(result.matchSetDigest());
        assertEquals(List.of(Warning.GLOSSARY_UNSUPPORTED_NORMALIZATION), result.warnings());
        assertEquals(digest(decomposed), result.sourceDigest());
    }

    @Test
    void theStatusesAndWarningsAreWrittenTheWayTheContractNamesThem() {
        assertEquals("no-match", json(Status.NO_MATCH).stringValue());
        assertEquals("matched", json(Status.MATCHED).stringValue());
        assertEquals("unavailable", json(Status.UNAVAILABLE).stringValue());
        assertEquals("limit-exceeded", json(Status.LIMIT_EXCEEDED).stringValue());
        assertEquals("unsupported-normalization", json(Status.UNSUPPORTED_NORMALIZATION).stringValue());
        assertEquals("GLOSSARY_UNAVAILABLE", json(Warning.GLOSSARY_UNAVAILABLE).stringValue());
    }

    // ------------------------------------------------------------------------------------------------------------------------- a digest that is not the text's

    private static void assertRefused(LookupRequest request, String messageKey) {
        InvalidInputException refused = assertThrows(InvalidInputException.class, () -> seeded.lookup(request));
        assertEquals("INVALID_INPUT", refused.code().wireCode());
        assertEquals(messageKey, refused.messageKey());
    }

    @Test
    void aDigestThatIsNotTheTextsIsRefused() {
        assertRefused(new LookupRequest("1.0.0", "가", "0".repeat(64)), "services.glossary.sourceDigestMismatch");
        assertRefused(new LookupRequest("1.0.0", "가", digest("가").toUpperCase(Locale.ROOT)), "services.glossary.sourceDigestMismatch");
        assertRefused(new LookupRequest("1.0.0", "가", "sha256:" + digest("가")), "services.glossary.sourceDigestMismatch");
        assertRefused(new LookupRequest("1.0.0", "가", digest("나")), "services.glossary.sourceDigestMismatch");
    }

    @Test
    void aRequestThatIsNotTheContractsIsRefusedBeforeAnyDigest() {
        for (LookupRequest request : List.of(new LookupRequest("2.0.0", "가", digest("가")), new LookupRequest(null, "가", digest("가")), new LookupRequest("1.0.0", null, digest("가")),
                new LookupRequest("1.0.0", "가", null))) {
            InvalidInputException refused = assertThrows(InvalidInputException.class, () -> seeded.lookup(request));
            assertEquals("INVALID_INPUT", refused.code().wireCode());
            assertEquals("workflow.schema.contractMismatch", refused.messageKey());
            assertEquals(Map.of("name", "koreanProseGlossaryLookupRequest"), refused.arguments());
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------- a catalog the glossary will not hold

    private static void assertUnavailable(String why, KoreanProseGlossary glossary) {
        LookupResult result = lookup(glossary, "가");
        assertEquals(Status.UNAVAILABLE, result.status(), why);
        assertNull(result.glossary(), why);
        assertNull(result.matchSetDigest(), why);
        assertEquals(List.of(), result.matches(), why);
        assertEquals(List.of(Warning.GLOSSARY_UNAVAILABLE), result.warnings(), why);
        assertEquals(digest("가"), result.sourceDigest(), why);
    }

    @Test
    void anEntryOfTheRightShapeIsHeld() {
        LookupResult result = lookup(over(entry("ok", "리포지토리", GlossaryPolicy.PREFER, 50, true, alias("레포지토리")), entry("fine", "Fine", GlossaryPolicy.ALLOW, 0, true, canonical("Fine"), alias("Alias"))),
                "레포지토리 Alias");
        assertEquals(Status.MATCHED, result.status());
        assertEquals(2, result.matches().size());
    }

    @Test
    void aCatalogThatCannotBeReadAnswersUnavailableForEveryLookup() {
        GlossaryCatalog broken = () -> {
            throw new DataAccessResourceFailureException("the database is down");
        };
        assertUnavailable("a database that cannot be reached", new KoreanProseGlossary(GlossaryJson.MAPPER, new GlossaryLoader(broken, GlossaryJson.MAPPER)));
        // The glossary's schema is dropped for the lookup and made again by the migrations at the end, so the other cases find the terms where they were.
        try (java.sql.Connection connection = database.getConnection(); java.sql.Statement drop = connection.createStatement()) {
            drop.execute("DROP SCHEMA sennard CASCADE");
            assertUnavailable("a database without the glossary's tables", new KoreanProseGlossary(GlossaryJson.MAPPER, new GlossaryLoader(new JdbcGlossaryCatalog(database), GlossaryJson.MAPPER)));
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException(failure);
        } finally {
            GlossaryDatabase.open();
        }
    }

    @Test
    void anEntryThatBreaksARuleOfTheGlossaryAnswersUnavailable() {
        assertUnavailable("an entry id that is not lower-case kebab", over(entry("Bad_Id", "A", GlossaryPolicy.PROTECT, 10, true, canonical("A"))));
        assertUnavailable("an empty canonical form", over(entry("ok", "", GlossaryPolicy.PREFER, 10, true, alias("B"))));
        assertUnavailable("a source that is not https", over(new GlossaryEntry("ok", "A", GlossaryPolicy.PROTECT, "http://example.test/ok", 10, GlossaryEntryStatus.ACTIVE, List.of(canonical("A")))));
        assertUnavailable("a priority over the range", over(entry("ok", "A", GlossaryPolicy.PROTECT, 1001, true, canonical("A"))));
        assertUnavailable("a priority under the range", over(entry("ok", "A", GlossaryPolicy.PROTECT, -1, true, canonical("A"))));
        assertUnavailable("no form", over(entry("ok", "A", GlossaryPolicy.PROTECT, 10, true)));
        assertUnavailable("an empty form", over(entry("ok", "A", GlossaryPolicy.PROTECT, 10, true, canonical("A"), canonical(""))));
        assertUnavailable("a form not in normalization form C", over(entry("ok", "A", GlossaryPolicy.PROTECT, 10, true, canonical("A"), canonical("가"))));
        assertUnavailable("a kind the policy does not take", over(entry("ok", "A", GlossaryPolicy.PROTECT, 10, true, canonical("A"), alias("B"))));
        assertUnavailable("a repeated form in one entry", over(entry("ok", "A", GlossaryPolicy.ALLOW, 10, true, canonical("A"), canonical("A"))));
        assertUnavailable("a protect entry that does not list its canonical form", over(entry("ok", "A", GlossaryPolicy.PROTECT, 10, true, canonical("B"))));
        assertUnavailable("an allow entry that lists its canonical form only as an alias", over(entry("ok", "A", GlossaryPolicy.ALLOW, 10, true, alias("A"))));
        assertUnavailable("a prefer entry whose form is its canonical form", over(entry("ok", "A", GlossaryPolicy.PREFER, 10, true, alias("A"))));
        assertUnavailable("an avoid entry whose form is its canonical form",
                over(entry("ok", "A", GlossaryPolicy.AVOID, 10, true, new GlossaryForm("A", GlossaryFormKind.DISCOURAGED))));
        assertUnavailable("a repeated entry id", over(entry("ok", "A", GlossaryPolicy.PROTECT, 10, true, canonical("A")), entry("ok", "B", GlossaryPolicy.PROTECT, 10, true, canonical("B"))));
        assertUnavailable("one form for two active entries", over(entry("one", "A", GlossaryPolicy.PROTECT, 10, true, canonical("A")),
                entry("two", "B", GlossaryPolicy.ALLOW, 10, true, canonical("B"), alias("A"))));
    }

    @Test
    void anInactiveEntryMayShareAFormWithAnActiveOne() {
        KoreanProseGlossary glossary = over(entry("on", "AB", GlossaryPolicy.PROTECT, 10, true, canonical("AB")), entry("off", "AB", GlossaryPolicy.PROTECT, 10, false, canonical("AB")));
        LookupResult result = lookup(glossary, "AB");
        assertEquals(Status.MATCHED, result.status());
        assertEquals(List.of("on"), result.matches().stream().map(Match::entryId).toList());
        assertNotNull(result.glossary());
    }

    // ------------------------------------------------------------------------------------------------------------------------- read once

    /** What the application gets from Spring Boot for caching: the auto-configuration of spring-boot-cache. */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.ImportAutoConfiguration(org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration.class)
    static class BootCache {
    }

    @Test
    void theCatalogIsReadOnceAndAFailedReadIsNotKept() {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger(1);
        GlossaryCatalog counting = () -> {
            reads.incrementAndGet();
            if (failures.getAndDecrement() > 0) {
                throw new DataAccessResourceFailureException("the database is not up yet");
            }
            return new GlossaryContent("korean-prose-core", "9.9.9", List.of(entry("ab", "AB", GlossaryPolicy.PROTECT, 10, true, canonical("AB"))));
        };
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(GlossaryCatalog.class, () -> counting);
            context.registerBean(tools.jackson.databind.json.JsonMapper.class, () -> GlossaryJson.MAPPER);
            context.register(BootCache.class, GlossaryCacheConfiguration.class, GlossaryLoader.class, KoreanProseGlossary.class);
            context.refresh();
            assertEquals("CaffeineCacheManager", context.getBean(org.springframework.cache.CacheManager.class).getClass().getSimpleName(), "the cache manager is Spring Boot's, made by CacheAutoConfiguration");
            KoreanProseGlossary glossary = context.getBean(KoreanProseGlossary.class);
            assertEquals(Status.UNAVAILABLE, lookup(glossary, "AB").status(), "the first read failed");
            assertEquals(Status.MATCHED, lookup(glossary, "AB").status(), "the database is up: read again, not kept as a failure");
            assertEquals(2, reads.get());
            lookup(glossary, "AB");
            lookup(glossary, "xAB");
            assertEquals(2, reads.get(), "kept once it was read");
        }
    }
}
