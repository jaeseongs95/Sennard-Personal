// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.build;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The text of THIRD_PARTY_NOTICES.md: one line per third-party component of the shipped class path, with its version, license names and project URL. It uses the JDK only, so
 * that its test runs in sennard-repo-tools with the rest of the build logic; the reading of the license report is in {@link ThirdPartyNoticesTask}. The text depends on nothing but
 * the entries (no time, no path), so the file in the repository is current exactly when it equals a fresh rendering.
 */
public final class ThirdPartyNotices {
    /** What the report says of one component: {@code name} is {@code group:artifact}; the lists hold the names and URLs as they stand after normalization. */
    public record Entry(String name, String version, List<String> licenses, List<String> urls) {
        public Entry {
            licenses = List.copyOf(licenses);
            urls = List.copyOf(urls);
        }
    }

    private static final String HEADER = """
            # Third-Party Notices

            Sennard is licensed under AGPL-3.0-or-later with the additional terms in ADDITIONAL-TERMS.md. The release archive also contains the components listed below, each under the license named
            for it. Where a component names more than one license, it is offered under any one of them. This file is generated from the dependency metadata of the shipped class path
            (`./gradlew :sennard-personal:generateThirdPartyNotices` in `java/`); do not edit it by hand.

            """;

    private ThirdPartyNotices() {
    }

    /** The whole file, LF line ends, sorted by name and version, without duplicates. */
    public static String render(List<Entry> entries) {
        String lines = entries.stream()
                .sorted(Comparator.comparing(Entry::name).thenComparing(Entry::version))
                .distinct()
                .map(ThirdPartyNotices::line)
                .collect(Collectors.joining("\n"));
        return HEADER + lines + "\n";
    }

    private static String line(Entry entry) {
        String licenses = entry.licenses().isEmpty() ? "(no license found)" : String.join("; ", entry.licenses());
        String urls = entry.urls().isEmpty() ? "" : " - " + String.join(" ", entry.urls());
        return "- " + entry.name() + " " + entry.version() + " - " + licenses + urls;
    }
}
