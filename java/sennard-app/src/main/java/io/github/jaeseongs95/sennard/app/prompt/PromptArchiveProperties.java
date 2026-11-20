// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.prompt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * The values the prompt archive uses ({@code sennard.prompt-archive.*}). {@code maxBytes} is the most UTF-8 bytes kept of one prompt; a longer prompt is cut at a character
 * boundary and marked cut ({@code PromptText}). Spring's {@code DataSize} reads {@code 1MB} as one mebibyte (1,048,576 bytes), the default.
 */
@ConfigurationProperties("sennard.prompt-archive")
public record PromptArchiveProperties(@DefaultValue("1MB") DataSize maxBytes) {
    public PromptArchiveProperties {
        if (maxBytes.toBytes() < 1) {
            throw new IllegalArgumentException("sennard.prompt-archive.max-bytes must keep at least one byte of a prompt");
        }
    }
}
