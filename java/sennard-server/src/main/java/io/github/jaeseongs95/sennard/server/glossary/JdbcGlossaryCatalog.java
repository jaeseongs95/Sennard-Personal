// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.glossary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.github.jaeseongs95.sennard.domain.glossary.GlossaryCatalog;
import io.github.jaeseongs95.sennard.domain.glossary.GlossaryContent;
import io.github.jaeseongs95.sennard.domain.glossary.GlossaryEntry;
import io.github.jaeseongs95.sennard.domain.glossary.GlossaryEntryStatus;
import io.github.jaeseongs95.sennard.domain.glossary.GlossaryForm;
import io.github.jaeseongs95.sennard.domain.glossary.GlossaryFormKind;
import io.github.jaeseongs95.sennard.domain.glossary.GlossaryPolicy;

/**
 * The glossary read from its three tables (db/expand/glossary of sennard-store-pg) over a DataSource, with ANSI SQL only: the same statements read H2 and PostgreSQL, and nothing
 * here names a database. The tables are reference data; this class only reads them (the migrations write them); the pool's connection is returned after each statement. The enums are
 * read by name (the column holds the constant's name, a CHECK lists the names). A database that cannot answer throws Spring's DataAccessException, which the loader turns into
 * "unavailable".
 */
@Repository
public class JdbcGlossaryCatalog implements GlossaryCatalog {
    private static final String METADATA = "SELECT glossary_id, version FROM sennard.glossary_metadata";
    private static final String ENTRIES = "SELECT entry_id, canonical_form, policy, source_ref, priority, status FROM sennard.glossary_entries";
    private static final String FORMS = "SELECT owner_entry_id, form_text, form_kind FROM sennard.glossary_forms ORDER BY owner_entry_id, ordinal";

    private record Id(String id, String version) {
    }

    private record EntryRow(String entryId, String canonicalForm, GlossaryPolicy policy, String sourceRef, int priority, GlossaryEntryStatus status) {
    }

    private record FormRow(String entryId, GlossaryForm form) {
    }

    private final JdbcClient jdbc;

    public JdbcGlossaryCatalog(@Qualifier("store") DataSource store) {
        this.jdbc = JdbcClient.create(store);
    }

    @Override
    public GlossaryContent load() {
        List<Id> ids = jdbc.sql(METADATA).query((rows, number) -> new Id(rows.getString(1), rows.getString(2))).list();
        if (ids.size() != 1) {
            throw new IllegalArgumentException("The glossary metadata has " + ids.size() + " rows, not one");
        }
        Map<String, List<GlossaryForm>> formsOf = new LinkedHashMap<>();
        for (FormRow row : jdbc.sql(FORMS).query((rows, number) -> new FormRow(rows.getString(1), new GlossaryForm(rows.getString(2), GlossaryFormKind.valueOf(rows.getString(3))))).list()) {
            formsOf.computeIfAbsent(row.entryId(), key -> new ArrayList<>()).add(row.form());
        }
        List<GlossaryEntry> entries = new ArrayList<>();
        for (EntryRow row : jdbc.sql(ENTRIES).query((rows, number) -> new EntryRow(rows.getString(1), rows.getString(2), GlossaryPolicy.valueOf(rows.getString(3)), rows.getString(4),
                rows.getInt(5), GlossaryEntryStatus.valueOf(rows.getString(6)))).list()) {
            entries.add(new GlossaryEntry(row.entryId(), row.canonicalForm(), row.policy(), row.sourceRef(), row.priority(), row.status(), formsOf.getOrDefault(row.entryId(), List.of())));
        }
        return new GlossaryContent(ids.get(0).id(), ids.get(0).version(), entries);
    }
}
