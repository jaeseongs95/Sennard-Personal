// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentSettings;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The complete attachment setting signed by the owner. Byte limits are exact JSON integers. */
public record AttachmentStorageSettings(@NotBlank @Size(max = 2000) String directory,
        @Min(1) @Max(9007199254740991L) long maxFileBytes,
        @Min(1) @Max(9007199254740991L) long stagingBudgetBytes,
        @Min(1) @Max(9007199254740991L) long attachmentBudgetBytes,
        @Min(1) int maxOpenPerOwner,
        @Min(0) @Max(9007199254740991L) long diskMarginBytes) {
    private static final Logger LOG = LoggerFactory.getLogger(AttachmentStorageSettings.class);

    public static AttachmentStorageSettings of(AttachmentSettings.Snapshot value) {
        return new AttachmentStorageSettings(value.directory().toString(), value.maxFileBytes(), value.stagingBudgetBytes(),
                value.attachmentBudgetBytes(), value.maxOpenPerOwner(), value.diskMarginBytes());
    }
    @AssertTrue
    @JsonIgnore
    public boolean isAbsoluteDirectory() {
        if (directory == null) return false;
        try {
            return Path.of(directory).isAbsolute();
        } catch (InvalidPathException invalid) {
            LOG.debug("Rejected attachment storage directory syntax: {}", invalid.getReason());
            return false;
        }
    }
    @AssertTrue
    @JsonIgnore
    public boolean isBudgetConsistent() {
        return maxFileBytes <= stagingBudgetBytes && stagingBudgetBytes <= attachmentBudgetBytes;
    }
}
