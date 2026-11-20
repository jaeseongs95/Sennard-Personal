// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.journal;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;
@Table(schema = "sennard", value = "observed_file")
public record ObservedFile(@Id String observedFileId, String workflowId, String path, String pathHash, Long sizeBytes, Instant modifiedAt, String sha256, @Version Long version) { }
