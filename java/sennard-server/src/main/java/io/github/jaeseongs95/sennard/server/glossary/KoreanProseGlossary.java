// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.glossary;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.glossary.GlossaryEntry;
import io.github.jaeseongs95.sennard.domain.glossary.GlossaryForm;
import io.github.jaeseongs95.sennard.domain.glossary.GlossaryPolicy;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.server.glossary.GlossaryLoader.LoadedGlossary;

import tools.jackson.databind.json.JsonMapper;

/**
 * The reviewed Korean terms: finds every occurrence of an active form and reports it with its policy. The terms come from the glossary's catalog (the database),
 * read and checked once by {@link GlossaryLoader}; a catalog that cannot be read or holds a term the glossary will not hold answers {@code unavailable} for every
 * call, with the cause in the log, rather than taking the optional glossary down with the server.
 */
@Service
public class KoreanProseGlossary {
    private static final Logger LOG = LoggerFactory.getLogger(KoreanProseGlossary.class);
    private static final String SCHEMA_VERSION = "1.0.0";
    private static final int MAX_SOURCE_LENGTH = 200_000;
    private static final int MAX_MATCHES = 256;
    /** Where matches begin first; at one start the longer, then the higher priority, then the entry id. */
    private static final Comparator<Match> ORDER = Comparator.comparingInt(Match::start).thenComparing(Comparator.comparingInt(Match::end).reversed())
            .thenComparing(Comparator.comparingInt(Match::priority).reversed()).thenComparing(Match::entryId);

    /** How a lookup ended. */
    public enum Status {
        @JsonProperty("matched") MATCHED, @JsonProperty("no-match") NO_MATCH, @JsonProperty("unavailable") UNAVAILABLE, @JsonProperty("limit-exceeded") LIMIT_EXCEEDED,
        @JsonProperty("unsupported-normalization") UNSUPPORTED_NORMALIZATION
    }

    /** Why a lookup is less than a full answer; the JSON value is the constant's name. */
    public enum Warning {
        GLOSSARY_MATCH_LIMIT_EXCEEDED, GLOSSARY_UNSUPPORTED_NORMALIZATION, GLOSSARY_UNAVAILABLE
    }

    public record Metadata(String id, String version, String contentDigest) {
    }

    public record Match(int start, int end, String entryId, GlossaryPolicy policy, String canonicalForm, int priority) {
    }

    /** What a caller sends: the contract's version, the text, and the hex SHA-256 of the text in UTF-8. */
    public record LookupRequest(String schemaVersion, String sourceText, String sourceDigest) {
    }

    public record LookupResult(String schemaVersion, Status status, String sourceDigest, Metadata glossary, List<Match> matches, String matchSetDigest, List<Warning> warnings) {
    }

    /** What the match set's digest is made over. */
    record MatchSetDigestInput(String schemaVersion, String sourceDigest, Metadata glossary, List<Match> matches) {
    }

    private final JsonMapper mapper;
    private final GlossaryLoader loader;

    public KoreanProseGlossary(JsonMapper mapper, GlossaryLoader loader) {
        this.mapper = mapper;
        this.loader = loader;
    }

    public LookupResult lookup(LookupRequest request) {
        String sourceText = request.sourceText();
        if (!SCHEMA_VERSION.equals(request.schemaVersion()) || sourceText == null || request.sourceDigest() == null) {
            throw new InvalidInputException(InputCode.TOOL_ARGUMENTS_MISMATCH, Map.of("name", "koreanProseGlossaryLookupRequest"));
        }
        String actual = Digests.sha256Hex(sourceText);
        if (!Objects.equals(actual, request.sourceDigest())) {
            throw new InvalidInputException(InputCode.GLOSSARY_SOURCE_DIGEST_MISMATCH);
        }
        if (sourceText.length() > MAX_SOURCE_LENGTH) {
            return empty(Status.LIMIT_EXCEEDED, actual, null, Warning.GLOSSARY_MATCH_LIMIT_EXCEEDED);
        }
        if (!Normalizer.isNormalized(sourceText, Normalizer.Form.NFC)) {
            return empty(Status.UNSUPPORTED_NORMALIZATION, actual, null, Warning.GLOSSARY_UNSUPPORTED_NORMALIZATION);
        }
        try {
            return match(loader.load(), sourceText, actual);
        } catch (GlossaryUnavailableException failed) {
            // The glossary is optional: a catalog that cannot be read is answered as "unavailable", and this log ends the handling.
            LOG.warn("The Korean prose glossary could not be read", failed);
            return empty(Status.UNAVAILABLE, actual, null, Warning.GLOSSARY_UNAVAILABLE);
        }
    }

    private LookupResult match(LoadedGlossary loaded, String sourceText, String actual) {
        List<Match> matches = new ArrayList<>();
        for (GlossaryEntry entry : loaded.entries()) {
            if (entry.active()) {
                for (GlossaryForm form : entry.forms()) {
                    for (int start = sourceText.indexOf(form.form()); start != -1; start = sourceText.indexOf(form.form(), start + 1)) {
                        matches.add(new Match(start, start + form.form().length(), entry.entryId(), entry.policy(), entry.canonicalForm(), entry.priority()));
                        if (matches.size() > MAX_MATCHES) {
                            return empty(Status.LIMIT_EXCEEDED, actual, loaded.metadata(), Warning.GLOSSARY_MATCH_LIMIT_EXCEEDED);
                        }
                    }
                }
            }
        }
        matches.sort(ORDER);
        String digest = Digests.sha256Hex(CanonicalJson.of(mapper.valueToTree(new MatchSetDigestInput(SCHEMA_VERSION, actual, loaded.metadata(), matches))));
        return new LookupResult(SCHEMA_VERSION, matches.isEmpty() ? Status.NO_MATCH : Status.MATCHED, actual, loaded.metadata(), List.copyOf(matches), digest, List.of());
    }

    private static LookupResult empty(Status status, String digest, Metadata glossary, Warning warning) {
        return new LookupResult(SCHEMA_VERSION, status, digest, glossary, List.of(), null, List.of(warning));
    }
}
