// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.server.hook.ShellPaths;
import io.github.jaeseongs95.sennard.server.hook.host.MsysShellPaths;

/**
 * The rules of the log that need no JSON: what an absolute path is (by the file system's own {@link Path}, not by the operating system's name), the names
 * of the chains and of a role.
 *
 * <pre>
 * a1  absolute    a real directory is absolute, and a path of a root (the file system's own root, a root with nothing after it, a root without a drive) is
 * a2  relative    a name, a path under a name, are not; a text that is no path at all is not
 * t1  targets     the fixed chains, a role body, a root approval and a binding in its encoded form are chains; anything else is not
 * r1  role name   NFC, trimmed, one line of at most 100 characters
 * </pre>
 */
class AdminLogTest {
    /** The shell's path spelling the log's rules read the Git Bash forms of a path through: the implementation the product has. */
    private static final ShellPaths SHELL = new MsysShellPaths();

    private static boolean absolute(String path) {
        return AdminLog.absolute(path, SHELL);
    }

    private static boolean exemptEntry(String entry) {
        return AdminLog.exemptEntry(entry, SHELL);
    }

    /** A1: the expectations are facts of the JDK on this file system, so they hold on every operating system without the test asking which one it is. */
    @Test
    void aRealDirectoryAndAPathOfARootAreAbsolute(@TempDir Path directory) {
        String separator = directory.getFileSystem().getSeparator();
        String root = directory.getRoot().toString();
        assertTrue(Path.of(directory.toString()).isAbsolute());
        assertTrue(absolute(directory.toString()), directory.toString());
        assertTrue(absolute(directory.resolve("child").toString()));
        assertTrue(absolute(root), "the root itself: " + root);
        assertTrue(Path.of(root).isAbsolute());
        // {@code /name}: absolute on a POSIX file system; on Windows a root without a drive, which is the Git Bash installation's own root.
        assertTrue(absolute("/name"));
        assertTrue(absolute("/tmp"));
    }

    /** A3: the drive-relative {@code C:name} (root without its separator, then a name) is no absolute path by {@link Path#isAbsolute}; on a POSIX file system it is a plain name. */
    @Test
    void aRootCutShortOfItsSeparatorIsNotAbsolute(@TempDir Path directory) {
        String separator = directory.getFileSystem().getSeparator();
        String root = directory.getRoot().toString();
        String cutShort = root.substring(0, root.length() - separator.length()) + "name";
        assertEquals(Path.of(cutShort).isAbsolute(), absolute(cutShort), cutShort);
        assertFalse(absolute(cutShort), cutShort);
    }

    /** G1: an entry with a glob character is a pattern; a plain path is not. */
    @Test
    void anEntryWithAGlobCharacterIsAPattern() {
        assertTrue(AdminLog.isGlob("/home/*/notes"));
        assertTrue(AdminLog.isGlob("/a/?/b"));
        assertTrue(AdminLog.isGlob("/a/[bc]/d"));
        assertTrue(AdminLog.isGlob("/a/{b,c}"));
        assertFalse(AdminLog.isGlob("/home/me/notes"));
    }

    /** G2: a well-formed pattern is a valid entry whatever the file system names a path; a pattern the file system refuses (unclosed {@code [} or {@code {}) is refused. */
    @Test
    void aPatternIsValidWhenTheFileSystemTakesItAsAGlob(@TempDir Path directory) {
        String escaped = directory.toString().replace("\\", "\\\\"); // glob syntax: a backslash is escaped (JDK FileSystem.getPathMatcher)
        assertTrue(exemptEntry(escaped + "/*/memory"));
        assertTrue(exemptEntry("/home/*/notes"));
        assertTrue(exemptEntry("/home/{a,b}/notes"));
        assertFalse(exemptEntry("/home/[abc/notes"));
        assertFalse(exemptEntry("/home/{a,b/notes"));
        assertTrue(exemptEntry(directory.toString()));
        assertFalse(exemptEntry("relative/dir"));
    }

    /** A2 */
    @Test
    void aNameARelativePathARootWithoutItsSeparatorAndNoPathAreNotAbsolute(@TempDir Path directory) {
        String separator = directory.getFileSystem().getSeparator();
        assertFalse(Path.of("name").isAbsolute());
        assertFalse(absolute("name"));
        assertFalse(absolute("a" + separator + "b"));
        assertFalse(absolute(""));
        assertFalse(absolute("." + separator + "x"));
        assertFalse(absolute(directory + "\0x"), "a NUL character is in no path");
    }

    /** T1 */
    @Test
    void aChainIsOneOfTheFixedOnesOrNamedAfterWhatItHolds() {
        for (String good : new String[] {"keys", "global", "roles", "jev", "jevKeys", "editExempt", "jvmPreset", "convergenceLimits", "roleBody:role-1", "rootApproval:a_1", "rootRelease:root-1", "binding:claude:session%201",
                "binding:claude:" + AdminLog.bindingTarget("h", "s ü").substring("binding:h:".length())}) {
            assertTrue(AdminLog.isTarget(good), good);
        }
        for (String bad : new String[] {"", "key", "roleBody:", "roleBody:a b", "rootApproval:", "rootRelease:", "rootRelease:a b", "binding:a:b:c", "binding:a b:c", "binding:a:b c", "binding:a%2:b", "binding::b", "Keys",
                "binding:" + "a".repeat(1000) + ":b"}) {
            assertFalse(AdminLog.isTarget(bad), bad);
        }
        assertEquals("binding:claude:s%201", AdminLog.bindingTarget("claude", "s 1"));
    }

    /** R1 */
    @Test
    void aRoleNameIsNormalizedTrimmedOneLineOfAtMost100Characters() {
        assertEquals("Reviewer", AdminLog.normalizeRoleName("  Reviewer\t"));
        assertEquals("é", AdminLog.normalizeRoleName("é"), "NFC");
        assertEquals("n".repeat(100), AdminLog.normalizeRoleName("n".repeat(100)));
        assertNull(AdminLog.normalizeRoleName("n".repeat(101)));
        assertNull(AdminLog.normalizeRoleName(""));
        assertNull(AdminLog.normalizeRoleName("   "));
        assertNull(AdminLog.normalizeRoleName("two\nlines"));
        assertNull(AdminLog.normalizeRoleName("be\u0007ll"));
        assertNull(AdminLog.normalizeRoleName("c\u00851"));
        assertNull(AdminLog.normalizeRoleName(null));
        assertEquals("reviewer", AdminLog.roleKey("  Reviewer "));
    }

    /** What is trimmed is what {@link String#strip()} trims (Character.isWhitespace), not what JavaScript's trim() trims: an em space goes, a no-break space stays. */
    @Test
    void aRoleNameIsTrimmedByTheJavaWhitespaceSet() {
        assertEquals("Reviewer", AdminLog.normalizeRoleName(" Reviewer "));
        assertEquals(" Reviewer", AdminLog.normalizeRoleName(" Reviewer"));
        assertEquals("reviewer", AdminLog.roleKey(" Reviewer "));
    }
}
