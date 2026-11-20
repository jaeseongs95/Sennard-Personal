// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.continuity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.service.store.RootState;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where a path is, on a real file system. Whether two spellings are one place is decided by the file system ({@link Files#isSameFile}), never by a
 * flag of the operating system: the expectation of every case below is that oracle, so the same test holds on a case-sensitive and a
 * case-insensitive file system.
 */
class SurfaceIdentityTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final SurfaceIdentity identity = new SurfaceIdentity(null, MAPPER);

    @TempDir
    Path temp;

    private Path file(String relative) throws IOException {
        Path file = temp.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "x");
    }

    /** S1: one existing file written with {@code ..}, {@code .} and a changed case: the keys are equal exactly when the file system says it is one file. */
    @Test
    void spellingsOfOneFileShareAKeyExactlyWhenTheFileSystemSaysSo() throws IOException {
        Path real = file("work/src/Main.java");
        Path base = identity.identify(real.toString());
        List<String> spellings = List.of(temp.resolve("work/other/../src/Main.java").toString(), temp.resolve("work/./src/Main.java").toString(),
                temp.toString().toUpperCase() + "/work/src/Main.java".replace('/', real.getFileSystem().getSeparator().charAt(0)),
                temp.resolve("work/src/main.java").toString(), temp.resolve("work/src/Other.java").toString());
        for (String spelling : spellings) {
            Path candidate = Path.of(spelling);
            boolean same = Files.exists(candidate) && Files.isSameFile(real, candidate);
            Path target = identity.identify(spelling);
            assertNotNull(target, spelling);
            assertEquals(same, base.equals(target), spelling);
        }
    }

    /**
     * S2: a file that does not exist yet keeps a stable key: the real place of its deepest existing folder plus the unresolved tail. A {@code ..} through a folder that does
     * not exist is the one place the key follows the file system and not the text: the folder {@code work/lib/../src} is {@code work/src} exactly when the file system resolves
     * that spelling to a directory (the oracle, as in S1), so the keys are equal exactly then.
     */
    @Test
    void aNewFileIsKeyedByItsExistingFolder() throws IOException {
        Files.createDirectories(temp.resolve("work/src"));
        Path plain = identity.identify(temp.resolve("work/src/New.java").toString());
        Path climbing = identity.identify(temp.resolve("work/lib/../src/New.java").toString());
        assertEquals(Files.isDirectory(temp.resolve("work/lib/../src")), plain.equals(climbing));
        assertEquals(temp.resolve("work/src").toRealPath().resolve("New.java"), plain);
        assertNotEquals(plain, identity.identify(temp.resolve("work/src/Newer.java").toString()));
    }

    /** S3: a relative text, and a text that is no path, have no identity. */
    @Test
    void aRelativeOrUnreadablePathHasNoIdentity() {
        assertNull(identity.identify("src/Main.java"));
        assertNull(identity.identify("bad\u0000name"));
    }

    /** S4: a file inside a folder that holds a {@code .git} entry is keyed by its physical place alone: the entry is not read, so no repository is part of the identity. */
    @Test
    void aFileInACheckoutIsKeyedByItsPhysicalPlaceOnly() throws IOException {
        Files.writeString(Files.createDirectories(temp.resolve("repo")).resolve(".git"), "not a pointer\n");
        Path source = file("repo/src/App.java");
        assertEquals(source.toRealPath(), identity.identify(source.toString()));
    }

    /** S5: the same relative path in two working copies of one repository is two places: neither covers the other. */
    @Test
    void theSameRelativePathInAnotherWorkingCopyIsAnotherPlace() throws IOException {
        Path inMain = identity.identify(file("main/src/App.java").toString());
        Path inCopy = identity.identify(file("copy/src/App.java").toString());
        assertNotEquals(inMain, inCopy);
        SurfaceIdentity.ActiveRoot root = new SurfaceIdentity.ActiveRoot("r", RootState.NEEDS_USER, List.of(inMain.getParent()));
        assertTrue(root.covers(inMain));
        assertFalse(root.covers(inCopy), "even a gated root covers only its own physical places");
    }

    /** S6: a stored surface covers whole path elements below it, and an empty surface covers everything. */
    @Test
    void aSurfaceCoversWholeElementsBelowIt() throws IOException {
        Path target = identity.identify(file("repo/src/App.java").toString());
        SurfaceIdentity.ActiveRoot root = new SurfaceIdentity.ActiveRoot("r", RootState.OPEN, List.of(temp.resolve("repo/src").toRealPath()));
        assertTrue(root.covers(target));
        assertFalse(root.covers(identity.identify(file("repo/srcx/App.java").toString())));
        assertTrue(new SurfaceIdentity.ActiveRoot("all", RootState.OPEN, List.of(Path.of(""))).covers(target));
    }

    /** S7: a stored identity document is read by its names: unknown keys (the entry, how a glob was widened, a host) are ignored, a missing {@code surfaces} is empty. */
    @Test
    void aStoredIdentityDocumentBindsToRecords() {
        SurfaceIdentity.IdentityDocument document = MAPPER.readValue(
                "{\"surfaces\":[{\"entry\":\"a\",\"physical\":\"/w/a\",\"conservative\":\"glob-prefix\"},{\"physical\":\"/x\"}],\"host\":\"h\"}", SurfaceIdentity.IdentityDocument.class);
        assertEquals("/w/a", document.surfaces().get(0).physical());
        assertEquals("/x", document.surfaces().get(1).physical());
        assertEquals(List.of(), MAPPER.readValue("{}", SurfaceIdentity.IdentityDocument.class).surfaces());
    }

    /** S8: a stored root binds its state by the constant name; a name that is no state is refused. */
    @Test
    void aStoredRootBindsItsStateAndRefusesUnknownNames() {
        assertEquals(RootState.NEEDS_USER, MAPPER.readValue("{\"state\":\"NEEDS_USER\",\"rootId\":\"r\"}", SurfaceIdentity.RootDocument.class).state());
        assertThrows(JacksonException.class, () -> MAPPER.readValue("{\"state\":\"NOT_A_STATE\"}", SurfaceIdentity.RootDocument.class));
    }
}
