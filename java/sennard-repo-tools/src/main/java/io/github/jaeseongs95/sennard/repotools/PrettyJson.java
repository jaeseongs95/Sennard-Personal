// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.JsonNode;

/**
 * JSON written the way the repository's committed manifests are: two-space indent, {@code "key": value}, empty containers on one line,
 * a final newline. Jackson's default pretty printer puts a space before the colon and a space inside an empty container, so a manifest it rewrote would differ in
 * every line; the printer here is Jackson's own, set to the repository's form ({@link Separators}, {@link DefaultIndenter}).
 */
public final class PrettyJson {
    private PrettyJson() {
    }

    public static String text(JsonNode value) {
        DefaultIndenter indent = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter(Separators.createDefaultInstance()
                .withObjectNameValueSpacing(Separators.Spacing.AFTER).withObjectEmptySeparator("").withArrayEmptySeparator(""))
                .withObjectIndenter(indent).withArrayIndenter(indent);
        return Repo.JSON.writer().with(printer).writeValueAsString(value) + "\n";
    }
}
