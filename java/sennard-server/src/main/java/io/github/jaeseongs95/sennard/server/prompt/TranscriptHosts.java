// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.prompt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.transcript.Transcript;
import io.github.jaeseongs95.sennard.domain.transcript.TranscriptLocation;
import io.github.jaeseongs95.sennard.domain.transcript.TranscriptLookup;
import io.github.jaeseongs95.sennard.domain.transcript.TranscriptQuery;
import io.github.jaeseongs95.sennard.domain.transcript.TranscriptRead;

/**
 * Picks the {@link Transcript} that reads a host's transcripts: every {@code Transcript} bean of the application, by the host name it reads. A host with no implementation is
 * answered as {@code Unsupported}, never as an empty result and never by a guess at its format.
 *
 * <p>6.0.0 has one implementation, Claude Code's ({@code claude-code}). Codex, Gemini CLI and Antigravity keep their transcripts in their own files and formats, which have not
 * been measured on the test server; reading them is a separate task after that measurement, and each will be one more {@code Transcript} bean, with nothing changed here.
 */
@Component
class TranscriptHosts {
    private final Map<String, Transcript> byHost;

    /** @throws IllegalStateException when two implementations claim the same host: which one reads it would be a guess */
    TranscriptHosts(List<Transcript> transcripts) {
        Map<String, Transcript> chosen = new LinkedHashMap<>();
        for (Transcript transcript : transcripts) {
            Transcript earlier = chosen.putIfAbsent(transcript.host(), transcript);
            if (earlier != null) {
                throw new IllegalStateException("Two transcript readers claim the host " + transcript.host() + ": " + earlier.getClass().getName() + " and " + transcript.getClass().getName());
            }
        }
        this.byHost = Map.copyOf(chosen);
    }

    /** Whether some implementation reads the transcripts of this host. */
    boolean reads(String host) {
        return byHost.containsKey(host);
    }

    TranscriptLookup locate(TranscriptQuery query) {
        Transcript transcript = byHost.get(query.host());
        return transcript == null ? new TranscriptLookup.Unsupported(query.host()) : transcript.locate(query);
    }

    TranscriptRead readAfter(TranscriptLocation location, int limit) {
        Transcript transcript = byHost.get(location.host());
        return transcript == null ? new TranscriptRead.Unsupported(location.host()) : transcript.readAfter(location, limit);
    }

    TranscriptRead continueAfter(TranscriptLocation location, String entryKey, int limit) {
        Transcript transcript = byHost.get(location.host());
        return transcript == null ? new TranscriptRead.Unsupported(location.host()) : transcript.continueAfter(location, entryKey, limit);
    }
}
