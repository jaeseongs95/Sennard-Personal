// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The whitespace check of the lines a commit or a branch adds ({@code git diff --check}, {@code git show --check}), on throwaway repositories. */
class WhitespaceCheckTest {
    private static final PersonIdent FIXTURE = new PersonIdent("Fixture", "fixture@example.invalid");

    @TempDir
    Path temporary;

    private Git git;

    @AfterEach
    void close() {
        if (git != null) {
            git.close();
        }
    }

    private void init() throws GitAPIException {
        git = Git.init().setDirectory(temporary.toFile()).setInitialBranch("main").call();
    }

    private String commit(String name, String content, String message) throws IOException, GitAPIException {
        Files.writeString(temporary.resolve(name), content, StandardCharsets.UTF_8);
        git.add().addFilepattern(".").call();
        return git.commit().setMessage(message).setAuthor(FIXTURE).setCommitter(FIXTURE).setSign(false).call().name();
    }

    private List<String> problems(String base) {
        return new WhitespaceCheck(new Repo(temporary)).problems(base);
    }

    @Test
    void aCleanCommitHasNoProblem() throws Exception {
        init();
        commit("a.txt", "one\ntwo\n", "clean");
        assertEquals(List.of(), problems(null));
    }

    @Test
    void trailingWhitespaceOfAnAddedLineIsReportedWithItsLine() throws Exception {
        init();
        commit("a.txt", "one\n", "first");
        commit("a.txt", "one\ntwo \nthree\t\nfour\r\n", "second");
        assertEquals(List.of("a.txt:2: trailing whitespace.", "a.txt:3: trailing whitespace.", "a.txt:4: trailing whitespace."), problems(null));
    }

    @Test
    void aLineTheCommitDidNotAddIsNotReported() throws Exception {
        init();
        commit("a.txt", "old \nkept\n", "first");
        commit("a.txt", "old \nkept\nnew\n", "second");
        assertEquals(List.of(), problems(null));
    }

    @Test
    void aSpaceBeforeATabInTheIndentAndABlankLineAtTheEndAreReported() throws Exception {
        init();
        commit("a.txt", "one\n", "first");
        commit("a.txt", "one\n \tindented\nlast\n\n", "second");
        assertEquals(List.of("a.txt:2: space before tab in indent.", "a.txt:4: new blank line at EOF."), problems(null));
    }

    @Test
    void aBinaryFileIsNotRead() throws Exception {
        init();
        Files.write(temporary.resolve("b.bin"), new byte[] {0, 1, ' ', '\n', 0});
        git.add().addFilepattern(".").call();
        git.commit().setMessage("binary").setAuthor(FIXTURE).setCommitter(FIXTURE).setSign(false).call();
        assertEquals(List.of(), problems(null));
    }

    @Test
    void theRootCommitIsComparedWithAnEmptyTree() throws Exception {
        init();
        commit("a.txt", "one \n", "root");
        assertEquals(List.of("a.txt:1: trailing whitespace."), problems(null));
    }

    @Test
    void aBaseChecksEveryCommitOfTheBranchSinceTheMergeBase() throws Exception {
        init();
        String base = commit("a.txt", "one\n", "base");
        commit("a.txt", "one\ntwo \n", "dirty");
        commit("a.txt", "one\ntwo \nthree\n", "clean after");
        // HEAD alone adds a clean line; against the base the whole branch is read.
        assertEquals(List.of(), problems(null));
        assertEquals(List.of("a.txt:2: trailing whitespace."), problems(base));
        assertEquals(List.of("a.txt:2: trailing whitespace."), problems(" " + base + " "));
        assertEquals(List.of(), problems(" "));
    }

    @Test
    void aBaseThatNamesNoCommitFails() throws Exception {
        init();
        commit("a.txt", "one\n", "base");
        assertThrows(RepoTools.Failed.class, () -> problems("no-such-branch"));
    }
}
