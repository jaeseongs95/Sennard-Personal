// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.util.io.DisabledOutputStream;

/**
 * The whitespace check of the lines a change adds, as {@code git diff --check} and {@code git show --check} make it with git's default rules, through JGit and not the git
 * program: trailing whitespace (a space, a tab or a carriage return before the end of the line), a space before a tab in the indent, and blank lines added at the end of a file.
 * The change is the commit {@code HEAD} against its first parent (none: against the empty tree), or, with a base, against the merge base of the base and {@code HEAD}
 * ({@code git diff base...HEAD}). Renames are not followed, a binary file is not read.
 */
final class WhitespaceCheck {
    private final Repo repo;

    WhitespaceCheck(Repo repo) {
        this.repo = repo;
    }

    /** One problem per line, as {@code path:line: what}. {@code base} may be null or blank. */
    List<String> problems(String base) {
        return repo.inGit(repository -> problems(repository, base == null || base.isBlank() ? null : base.strip()));
    }

    private static List<String> problems(Repository repository, String base) throws IOException {
        List<String> problems = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repository); ObjectReader reader = repository.newObjectReader(); DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            RevCommit head = walk.parseCommit(repository.resolve("HEAD"));
            RevCommit from = origin(repository, walk, head, base);
            AbstractTreeIterator before = from == null ? new EmptyTreeIterator() : tree(reader, from);
            formatter.setRepository(repository);
            formatter.setDetectRenames(false);
            for (DiffEntry entry : formatter.scan(before, tree(reader, head))) {
                if (entry.getChangeType() == DiffEntry.ChangeType.DELETE) {
                    continue;
                }
                byte[] added = repository.open(entry.getNewId().toObjectId()).getBytes();
                if (RawText.isBinary(added)) {
                    continue;
                }
                RawText old = entry.getChangeType() == DiffEntry.ChangeType.ADD ? RawText.EMPTY_TEXT : new RawText(repository.open(entry.getOldId().toObjectId()).getBytes());
                RawText current = new RawText(added);
                EditList edits = new HistogramDiff().diff(RawTextComparator.DEFAULT, old, current);
                problems.addAll(inEdits(entry.getNewPath(), current, edits));
            }
        }
        return problems;
    }

    /** The commit the change starts from: the merge base with the base, the first parent without one, null for a root commit. */
    private static RevCommit origin(Repository repository, RevWalk walk, RevCommit head, String base) throws IOException {
        if (base == null) {
            return head.getParentCount() == 0 ? null : walk.parseCommit(head.getParent(0));
        }
        ObjectId baseId = repository.resolve(base + "^{commit}");
        if (baseId == null) {
            throw new RepoTools.Failed("no commit named " + base);
        }
        try (RevWalk bases = new RevWalk(repository)) {
            bases.setRevFilter(RevFilter.MERGE_BASE);
            bases.markStart(bases.parseCommit(baseId));
            bases.markStart(bases.parseCommit(head));
            RevCommit mergeBase = bases.next();
            if (mergeBase == null) {
                throw new RepoTools.Failed("no merge base of " + base + " and HEAD");
            }
            return walk.parseCommit(mergeBase);
        }
    }

    private static CanonicalTreeParser tree(ObjectReader reader, RevCommit commit) throws IOException {
        CanonicalTreeParser parser = new CanonicalTreeParser();
        parser.reset(reader, commit.getTree());
        return parser;
    }

    /** The problems of the lines the edits insert into {@code current}. */
    static List<String> inEdits(String path, RawText current, EditList edits) {
        List<String> problems = new ArrayList<>();
        for (Edit edit : edits) {
            for (int line = edit.getBeginB(); line < edit.getEndB(); line++) {
                String text = current.getString(line);
                if (endsInWhitespace(text)) {
                    problems.add(path + ":" + (line + 1) + ": trailing whitespace.");
                } else if (spaceBeforeTab(text)) {
                    problems.add(path + ":" + (line + 1) + ": space before tab in indent.");
                }
            }
            int last = edit.getEndB() - 1;
            if (edit.getEndB() == current.size() && last >= edit.getBeginB() && current.getString(last).isBlank()) {
                problems.add(path + ":" + (last + 1) + ": new blank line at EOF.");
            }
        }
        return problems;
    }

    private static boolean endsInWhitespace(String text) {
        if (text.isEmpty()) {
            return false;
        }
        char end = text.charAt(text.length() - 1);
        return end == ' ' || end == '\t' || end == '\r';
    }

    /** A space directly before a tab, both inside the indent (the whitespace the line starts with). */
    private static boolean spaceBeforeTab(String text) {
        int index = 0;
        while (index < text.length() && (text.charAt(index) == ' ' || text.charAt(index) == '\t')) {
            if (text.charAt(index) == ' ' && index + 1 < text.length() && text.charAt(index + 1) == '\t') {
                return true;
            }
            index++;
        }
        return false;
    }
}
