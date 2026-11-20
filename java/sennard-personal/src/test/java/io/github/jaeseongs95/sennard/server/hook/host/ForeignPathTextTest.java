// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Path text written for another platform, read the same on every host (the one place of it besides MsysShellPaths). */
class ForeignPathTextTest {
    @Test
    void aLocatorHasOneCanonicalFormWhateverPlatformItIsWrittenFor() {
        assertEquals("c:/work/repo", ForeignPathText.canonicalLocator("C:\\work\\repo\\"));
        assertEquals("c:/work/repo", ForeignPathText.canonicalLocator("c:/work//repo/./"));
        assertEquals("c:", ForeignPathText.canonicalLocator("C:"));
        assertEquals("//server/share/dir", ForeignPathText.canonicalLocator("\\\\server\\share\\dir\\"));
        assertEquals("/usr/local", ForeignPathText.canonicalLocator("/usr//local/bin/.."));
        assertEquals("/", ForeignPathText.canonicalLocator("/"));
        assertEquals("../x", ForeignPathText.canonicalLocator("a/../../x"));
        assertEquals("https://example.invalid/a/b", ForeignPathText.canonicalLocator("HTTPS://example.invalid//a//b/"));
    }

    @Test
    void aBackslashIsASeparatorOfAnotherPlatformAndASlashedScopeReadsItAsOne() {
        assertTrue(ForeignPathText.hasBackslash("a\\b"));
        assertFalse(ForeignPathText.hasBackslash("a/b"));
        assertEquals("src/main/", ForeignPathText.slashed("src\\main\\"));
    }

    @Test
    void aDriveLetterIsRecognisedOnlyAsAnAsciiLetterAndAColon() {
        assertTrue(ForeignPathText.looksLikeDrivePath("C:foo"));
        assertTrue(ForeignPathText.looksLikeDrivePath("z:/x"));
        assertFalse(ForeignPathText.looksLikeDrivePath("src:x"));
        assertFalse(ForeignPathText.looksLikeDrivePath("1:x"));
        assertFalse(ForeignPathText.looksLikeDrivePath("\uAC00:x"));
        assertFalse(ForeignPathText.looksLikeDrivePath("c"));
    }

    @Test
    void theComponentsOfAPortablePathKeepTheEmptyOnesThatPathWouldDrop() {
        assertEquals(List.of("a", "b", "c"), ForeignPathText.components("a/b/c"));
        assertEquals(List.of("a", "", "b"), ForeignPathText.components("a//b"));
        assertEquals(List.of("a", ""), ForeignPathText.components("a/"));
    }

    @Test
    void twoPathsOverlapWhenTheSameOrOneLiesUnderTheOtherOnWholeComponents() {
        assertTrue(ForeignPathText.overlaps("src", "src"));
        assertTrue(ForeignPathText.overlaps("src/main", "src"));
        assertTrue(ForeignPathText.overlaps("src", "src/main"));
        assertFalse(ForeignPathText.overlaps("src", "srcx"));
        assertFalse(ForeignPathText.overlaps("src/a", "src/b"));
    }
}
