// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jaeseongs95.sennard.build.ThirdPartyNotices.Entry;
import java.util.List;
import org.junit.jupiter.api.Test;

class ThirdPartyNoticesTest {
    private static final Entry B = new Entry("org.b:b", "1.0", List.of("MIT License"), List.of("https://b.example"));
    private static final Entry A2 = new Entry("org.a:a", "2.0", List.of("Apache License, Version 2.0"), List.of());
    private static final Entry A1 = new Entry("org.a:a", "1.0", List.of("Apache License, Version 2.0", "MIT License"), List.of("https://a.example"));

    @Test
    void entriesAreSortedByNameThenVersionAndListedOnce() {
        String text = ThirdPartyNotices.render(List.of(B, A2, A1, A1));
        assertEquals(List.of(
                "- org.a:a 1.0 - Apache License, Version 2.0; MIT License - https://a.example",
                "- org.a:a 2.0 - Apache License, Version 2.0",
                "- org.b:b 1.0 - MIT License - https://b.example"),
                text.lines().filter(line -> line.startsWith("- ")).toList());
    }

    @Test
    void theSameEntriesInAnyOrderGiveTheSameText() {
        assertEquals(ThirdPartyNotices.render(List.of(A1, A2, B)), ThirdPartyNotices.render(List.of(B, A1, A2)));
    }

    @Test
    void aComponentWithoutALicenseIsStatedAsSuch() {
        String text = ThirdPartyNotices.render(List.of(new Entry("x:y", "3", List.of(), List.of())));
        assertTrue(text.contains("- x:y 3 - (no license found)\n"));
    }

    @Test
    void theTextUsesLfAndEndsWithOneLineEnd() {
        String text = ThirdPartyNotices.render(List.of(A1));
        assertFalse(text.contains("\r"));
        assertTrue(text.endsWith("\n") && !text.endsWith("\n\n"));
        assertTrue(text.startsWith("# Third-Party Notices\n"));
    }
}
