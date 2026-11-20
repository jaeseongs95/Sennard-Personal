// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.prompt;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.IdGenerator;
import org.springframework.validation.annotation.Validated;

import io.github.jaeseongs95.sennard.domain.prompt.PromptText;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The only way the user's words get into the archive, and the only class that writes them. A prompt is stored once, as it came (the first part of it when it is over
 * {@link PromptArchiveProperties#maxBytes()}), with its size and digest. It is written from the hook path and from nowhere else: the tools the agent calls read the archive
 * ({@link PromptArchiveReader}) and record a recap ({@link TurnRecapService}), they cannot add or change a word the user said. The machine check
 * ({@code PromptArchiveRules}) holds the MCP tool classes to that.
 *
 * <p>There is no retry: the identifier is new for every call, so there is no key another writer can take, and a failure is the caller's to see (the hook does not swallow it).
 */
@Service
@Validated
public class PromptArchiveWriter {
    /** What was stored: the prompt's identifier, when it was received (the injected clock's instant) and whether only a first part of it was kept. */
    public record Archived(String userPromptId, Instant receivedAt, long originalBytes, boolean truncated) {
    }

    private final UserPromptWrites prompts;
    private final PromptArchiveProperties properties;
    private final IdGenerator ids;
    private final Clock clock;

    public PromptArchiveWriter(UserPromptWrites prompts, PromptArchiveProperties properties, IdGenerator ids, Clock clock) {
        this.prompts = prompts;
        this.properties = properties;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * @param prompt the user's text as the host gave it, possibly empty
     * @param transcriptPath where the host keeps the session's transcript, when the host said; null otherwise
     */
    @Transactional
    public Archived store(@NotBlank @Size(max = 64) String host, @NotBlank @Size(max = 512) String sessionId, @NotNull String prompt, String transcriptPath) {
        return storeReceivedAt(host, sessionId, prompt, transcriptPath, clock.instant());
    }

    /** Archives input after its hook's conversation is confirmed, keeping the hook's original receipt time. */
    @Transactional
    public Archived storeReceivedAt(@NotBlank @Size(max = 64) String host, @NotBlank @Size(max = 512) String sessionId, @NotNull String prompt,
            String transcriptPath, @NotNull Instant receivedAt) {
        PromptText kept = PromptText.of(prompt, properties.maxBytes().toBytes());
        String id = ids.generateId().toString();
        Instant at = receivedAt.truncatedTo(ChronoUnit.MILLIS);
        prompts.insert(id, host, sessionId, at, kept.text(), kept.originalBytes(), kept.digest(), Completeness.of(kept.truncated()).name(),
                transcriptPath == null || transcriptPath.isEmpty() ? null : transcriptPath);
        return new Archived(id, at, kept.originalBytes(), kept.truncated());
    }
}
