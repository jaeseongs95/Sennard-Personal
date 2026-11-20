// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.journal;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;
@Table(schema = "sennard", value = "change_event")
public record ChangeEvent(@Id String changeEventId, String workflowId, long planRevision, String path, String pathHash, String sha256,
        Long sizeBytes, Instant modifiedAt, Instant recordedAt, ChangeSource source, @Version Long version) { }
