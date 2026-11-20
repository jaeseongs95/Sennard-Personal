// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.glossary;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.service.store.pg.BusinessDb;
import tools.jackson.databind.node.ObjectNode;

/**
 * The glossary's tables on the PostgreSQL database the test run names ({@link BusinessDb}), made by the migrations of sennard-store-pg: V13 (the tables) and the data script (V16). The
 * test reads the data with SQL of its own, not through the catalog under test.
 */
final class GlossaryDatabase {
    /** The 22 terms version 1.3.0 added to the 243 of 1.2.1. */
    static final Set<String> ADDED_IN_1_3_0 = Set.of("java", "jdk", "jdbc", "spring-boot", "spring-framework", "gradle", "junit", "postgresql", "claude-code", "gemini-cli", "antigravity", "logback", "slf4j",
            "jackson", "flyway", "content-ko", "shell-ko", "concept-ko", "message-ko", "directory-ko", "adapter-ko", "browser-ko");
    /** The content digest of the 243 terms of version 1.2.1, as the golden answer of the lookup tool had it before the terms moved into the database. */
    // Re-pinned in 6.0.0: the rename changed the repository URL in the source_ref of completion-result-ko to sennard.
    static final String DIGEST_OF_1_2_1 = "10ab147d3fb95b9b5d920e060036a045b12de0c2aec1c742daa12eb8b9037169";

    /** The scripts as files, for the cases that run one of them again over the migrated database. */
    private static final Path SCRIPTS = Path.of(System.getProperty("sennard.repo.root")).resolve("java/sennard-store-pg/src/main/resources/db/expand/glossary");

    private GlossaryDatabase() {
    }

    static Path tablesScript() {
        return SCRIPTS.resolve("V13__glossary_tables.sql");
    }

    static Path dataScript() {
        return SCRIPTS.resolve("V16__korean_prose_glossary.sql");
    }

    /** The business database of the test run, started from the schemas dropped and migrated anew: the migrations make the tables and load the terms. */
    static DataSource open() {
        try {
            BusinessDb.reset();
        } catch (SQLException unreachable) {
            throw new IllegalStateException("The shared business database cannot be reset: " + unreachable.getMessage(), unreachable);
        }
        return source();
    }

    /** A DataSource of the business database as it is now: the test JVM's one pool ({@link BusinessDb#pool()}), which a test does not close. */
    static DataSource source() {
        return BusinessDb.pool();
    }

    /**
     * The content digest the contract defines, made from the rows without the catalog or the loader: entries by id, each with its seven members and its forms by spelling then kind,
     * the enums as their lower-case JSON words, the whole as canonical JSON. {@code leaving} names entries to leave out (the terms a later version added).
     */
    static String contentDigest(DataSource source, Set<String> leaving) {
        JdbcClient jdbc = JdbcClient.create(source);
        List<ObjectNode> entries = new ArrayList<>();
        for (String id : jdbc.sql("SELECT entry_id FROM sennard.glossary_entries").query(String.class).list().stream().filter(entry -> !leaving.contains(entry)).sorted().toList()) {
            List<ObjectNode> forms = new ArrayList<>(jdbc.sql("SELECT form_text, form_kind FROM sennard.glossary_forms WHERE owner_entry_id = ?").param(id)
                    .query((rows, number) -> GlossaryJson.object("form", rows.getString(1), "kind", rows.getString(2).toLowerCase(Locale.ROOT))).list());
            forms.sort(Comparator.comparing((ObjectNode form) -> form.get("form").stringValue()).thenComparing(form -> form.get("kind").stringValue().toUpperCase(Locale.ROOT)));
            entries.add(jdbc.sql("SELECT canonical_form, policy, source_ref, priority, status FROM sennard.glossary_entries WHERE entry_id = ?").param(id)
                    .query((rows, number) -> GlossaryJson.object("entryId", id, "canonicalForm", rows.getString(1), "policy", rows.getString(2).toLowerCase(Locale.ROOT), "sourceRef", rows.getString(3),
                            "priority", rows.getInt(4), "active", "ACTIVE".equals(rows.getString(5)), "forms", GlossaryJson.array(forms)))
                    .single());
        }
        return Digests.sha256Hex(CanonicalJson.of(GlossaryJson.array(entries)));
    }
}
