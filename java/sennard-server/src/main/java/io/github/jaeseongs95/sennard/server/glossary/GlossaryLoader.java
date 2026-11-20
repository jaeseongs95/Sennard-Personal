// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.glossary;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.glossary.GlossaryCatalog;
import io.github.jaeseongs95.sennard.domain.glossary.GlossaryContent;
import io.github.jaeseongs95.sennard.domain.glossary.GlossaryEntry;
import io.github.jaeseongs95.sennard.domain.glossary.GlossaryForm;
import io.github.jaeseongs95.sennard.domain.glossary.GlossaryFormKind;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.server.glossary.KoreanProseGlossary.Metadata;

import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the glossary from its catalog, checks it and makes its content digest. The terms change only when a migration writes them, so the result is kept by Spring's
 * cache (cache {@value #CACHE}) after the first successful read; a failed read is not kept, so the glossary answers again once its database does. The catalog's rows
 * are ordered here (entries by id, forms by spelling then kind), not by the database, so the digest does not depend on how a database sorts.
 */
@Component
public class GlossaryLoader {
    static final String CACHE = "korean-prose-glossary";
    private static final int MAX_PRIORITY = 1000;
    private static final Pattern ENTRY_ID = Pattern.compile("[a-z0-9][a-z0-9-]*");
    /** A source reference is a web address under https (the scheme alone decides). */
    private static final Pattern HTTPS_REFERENCE = Pattern.compile("(?i)https:[^\\s]+");

    /** The checked, ordered entries and the metadata (id, version, content digest) they were made into. */
    public record LoadedGlossary(List<GlossaryEntry> entries, Metadata metadata) {
    }

    private final GlossaryCatalog catalog;
    private final JsonMapper mapper;

    public GlossaryLoader(GlossaryCatalog catalog, JsonMapper mapper) {
        this.catalog = catalog;
        this.mapper = mapper;
    }

    @Cacheable(CACHE)
    public LoadedGlossary load() {
        try {
            GlossaryContent content = catalog.load();
            List<GlossaryEntry> entries = checked(content.entries());
            return new LoadedGlossary(entries, new Metadata(content.id(), content.version(), Digests.sha256Hex(CanonicalJson.of(mapper.valueToTree(entries)))));
        } catch (DataAccessException | IllegalArgumentException failed) {
            throw new GlossaryUnavailableException("The glossary's catalog could not be read as a glossary", failed);
        }
    }

    private List<GlossaryEntry> checked(List<GlossaryEntry> loaded) {
        List<GlossaryEntry> parsed = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Map<String, String> activeForms = new HashMap<>();
        for (GlossaryEntry bound : loaded) {
            GlossaryEntry entry = validated(bound);
            if (!ids.add(entry.entryId())) {
                throw new IllegalArgumentException("Duplicate glossary entryId");
            }
            if (entry.active()) {
                for (GlossaryForm form : entry.forms()) {
                    String owner = activeForms.putIfAbsent(form.form(), entry.entryId());
                    if (owner != null && !Objects.equals(owner, entry.entryId())) {
                        throw new IllegalArgumentException("Conflicting active glossary form");
                    }
                }
            }
            parsed.add(entry);
        }
        parsed.sort(Comparator.comparing(GlossaryEntry::entryId));
        return List.copyOf(parsed);
    }

    /** The entry with its forms in the order the content digest is made over, or the reason the glossary will not hold it. */
    private GlossaryEntry validated(GlossaryEntry entry) {
        String canonical = entry.canonicalForm();
        boolean https = HTTPS_REFERENCE.matcher(entry.sourceRef()).matches();
        if (!ENTRY_ID.matcher(entry.entryId()).matches() || canonical.isEmpty() || !Normalizer.isNormalized(canonical, Normalizer.Form.NFC) || !https || entry.priority() < 0
                || entry.priority() > MAX_PRIORITY || entry.forms().isEmpty()) {
            throw new IllegalArgumentException("Glossary values");
        }
        Set<String> seen = new HashSet<>();
        for (GlossaryForm form : entry.forms()) {
            if (form.form().isEmpty() || !Normalizer.isNormalized(form.form(), Normalizer.Form.NFC) || !entry.policy().allows(form.kind()) || !seen.add(form.form())) {
                throw new IllegalArgumentException("Glossary form");
            }
        }
        boolean names = entry.forms().stream().anyMatch(form -> Objects.equals(form.form(), canonical));
        boolean listsCanonical = entry.forms().stream().anyMatch(form -> form.kind() == GlossaryFormKind.CANONICAL && Objects.equals(form.form(), canonical));
        if (entry.policy().listsCanonicalForm() ? !listsCanonical : names) {
            throw new IllegalArgumentException("Glossary canonical form");
        }
        List<GlossaryForm> forms = new ArrayList<>(entry.forms());
        forms.sort(Comparator.comparing(GlossaryForm::form).thenComparing(form -> form.kind().name()));
        return new GlossaryEntry(entry.entryId(), canonical, entry.policy(), entry.sourceRef(), entry.priority(), entry.status(), List.copyOf(forms));
    }
}
