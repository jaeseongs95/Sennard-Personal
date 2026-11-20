// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The repository the tools work on: its root, its files, and git read and written inside it through JGit, never through the git program. */
public final class Repo {
    /** The one mapper of the tools: they are a program of their own with no Spring context to inject one from. */
    public static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path root;

    public Repo(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Path path(String relative) {
        return root.resolve(relative);
    }

    public boolean exists(String relative) {
        return Files.exists(path(relative));
    }

    public byte[] bytes(String relative) {
        try {
            return Files.readAllBytes(path(relative));
        } catch (IOException e) {
            throw new UncheckedIOException(relative, e);
        }
    }

    public String text(String relative) {
        try {
            return Files.readString(path(relative), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(relative, e);
        }
    }

    public JsonNode json(String relative) {
        try {
            return JSON.readTree(bytes(relative));
        } catch (JacksonException e) {
            throw new IllegalStateException("invalid JSON " + relative, e);
        }
    }

    public void write(String relative, byte[] content) {
        try {
            Path target = path(relative);
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException(relative, e);
        }
    }

    public void write(String relative, String content) {
        write(relative, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Tracked and untracked-but-not-ignored files that exist, as sorted POSIX paths. */
    public List<String> trackedFiles() {
        return inGit(repository -> {
            TreeSet<String> files = new TreeSet<>();
            DirCache index = repository.readDirCache();
            for (int entry = 0; entry < index.getEntryCount(); entry++) {
                files.add(index.getEntry(entry).getPathString());
            }
            try (Git git = new Git(repository)) {
                files.addAll(git.status().call().getUntracked());
            } catch (GitAPIException e) {
                throw new GitFailure("status failed: " + e.getMessage(), e);
            }
            return files.stream().filter(this::exists).toList();
        });
    }

    /** Every file under a directory as POSIX paths relative to the repository root, sorted. */
    public List<String> walk(String directory) {
        Path start = path(directory);
        if (!Files.isDirectory(start)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(start)) {
            return files.filter(Files::isRegularFile).map(file -> root.relativize(file)).map(Repo::posix).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(directory, e);
        }
    }

    /**
     * A relative path as git writes it: its names joined by a slash. The paths this tool passes around ({@code String relative}) are the repository's own: names of
     * git tree entries, of zip entries and of the files a manifest lists, which are written with {@code /} on every file system, so they are kept as that text and
     * built and taken apart with {@link Path} ({@link #join}, {@link #under}), never by joining or cutting the text.
     */
    static String posix(Path relative) {
        List<String> names = new ArrayList<>();
        relative.forEach(name -> names.add(name.toString()));
        return String.join("/", names);
    }

    /** The path of a file in the repository: the names given, as git writes it. */
    static String join(String first, String... more) {
        return posix(Path.of(first, more));
    }

    /** The path of a file of {@code directory}, relative to it, as git writes it. */
    static String under(String directory, String file) {
        return posix(Path.of(directory).relativize(Path.of(file)));
    }

    /** One file of a commit's tree: its mode as git writes it ({@code 100644}, {@code 100755}, {@code 120000}, {@code 160000}), its object id and its path. */
    public record TreeEntry(String mode, String sha, String path) {
    }

    /** The full id of the commit a ref (a branch, a tag, an id) names; a {@link GitFailure} when it names none. */
    public String resolveCommit(String ref) {
        return inGit(repository -> {
            ObjectId id = repository.resolve(ref + "^{commit}");
            if (id == null) {
                throw new GitFailure("no commit named " + ref);
            }
            return id.name();
        });
    }

    /** Every file of a commit's tree, in git's order. */
    public List<TreeEntry> listTree(String commit) {
        return inGit(repository -> {
            List<TreeEntry> entries = new ArrayList<>();
            try (RevWalk revisions = new RevWalk(repository); TreeWalk walk = new TreeWalk(repository)) {
                walk.addTree(revisions.parseCommit(ObjectId.fromString(commit)).getTree());
                walk.setRecursive(true);
                while (walk.next()) {
                    entries.add(new TreeEntry(Integer.toOctalString(walk.getFileMode(0).getBits()), walk.getObjectId(0).name(), walk.getPathString()));
                }
            }
            return entries;
        });
    }

    /** The content of a blob. */
    public byte[] blob(String sha) {
        return inGit(repository -> repository.open(ObjectId.fromString(sha)).getBytes());
    }

    /** The url of a remote, null when the remote is not configured. */
    public String remoteUrl(String remote) {
        return inGit(repository -> repository.getConfig().getString("remote", remote, "url"));
    }

    /** Whether a full ref name such as {@code refs/heads/main} exists. */
    public boolean refExists(String ref) {
        return inGit(repository -> repository.exactRef(ref) != null);
    }

    /**
     * A zip of the files of {@code ref}'s tree that lie under one of {@code paths} (a file, or a folder and everything in it) and match none of {@code excludeGlobs}
     * (globs on the path from the root, {@code *} within a folder name and {@code **} across folders), every name behind {@code prefix}. Entries carry the commit's
     * time and the zip's comment is the commit's id, as {@code git archive --format=zip} writes them.
     */
    public void archiveZip(String ref, String prefix, Path out, List<String> paths, List<String> excludeGlobs) {
        List<PathMatcher> excluded = excludeGlobs.stream().map(glob -> FileSystems.getDefault().getPathMatcher("glob:" + glob)).toList();
        inGit(repository -> {
            ObjectId id = repository.resolve(ref + "^{commit}");
            if (id == null) {
                throw new GitFailure("no commit named " + ref);
            }
            try (RevWalk revisions = new RevWalk(repository); TreeWalk walk = new TreeWalk(repository);
                    ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
                RevCommit commit = revisions.parseCommit(id);
                zip.setComment(commit.name());
                long time = Instant.ofEpochSecond(commit.getCommitTime()).toEpochMilli();
                walk.addTree(commit.getTree());
                walk.setRecursive(true);
                while (walk.next()) {
                    String path = walk.getPathString();
                    if (walk.getFileMode(0) == FileMode.GITLINK || paths.stream().noneMatch(under -> path.equals(under) || path.startsWith(under + "/"))
                            || excluded.stream().anyMatch(matcher -> matcher.matches(Path.of(path)))) {
                        continue;
                    }
                    ZipEntry entry = new ZipEntry(prefix + path);
                    entry.setTime(time);
                    zip.putNextEntry(entry);
                    repository.open(walk.getObjectId(0)).copyTo(zip);
                    zip.closeEntry();
                }
            }
            return null;
        });
    }

    /** One file of a snapshot: its git mode ({@code 100644} or {@code 100755}), its path and its content. */
    public record SnapshotFile(String mode, String path, byte[] data) {
    }

    /**
     * Creates the branch {@code branch} (a full ref name that must not exist yet) with one commit on top of {@code base} whose tree is exactly {@code files}: the files
     * of the parent are not carried over. Author and committer are the repository's configured identity. Nothing is pushed.
     *
     * @return the id of the commit
     */
    public String commitSnapshot(String branch, String base, String message, List<SnapshotFile> files) {
        return inGit(repository -> {
            try (ObjectInserter inserter = repository.newObjectInserter()) {
                DirCache tree = DirCache.newInCore();
                DirCacheBuilder builder = tree.builder();
                for (SnapshotFile file : files) {
                    DirCacheEntry entry = new DirCacheEntry(file.path());
                    entry.setFileMode(FileMode.fromBits(Integer.parseInt(file.mode(), 8)));
                    entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, file.data()));
                    builder.add(entry);
                }
                builder.finish();
                PersonIdent identity = new PersonIdent(repository);
                CommitBuilder commit = new CommitBuilder();
                commit.setTreeId(tree.writeTree(inserter));
                commit.setParentId(ObjectId.fromString(base));
                commit.setAuthor(identity);
                commit.setCommitter(identity);
                commit.setMessage(message);
                ObjectId id = inserter.insert(commit);
                inserter.flush();
                RefUpdate update = repository.updateRef(branch);
                update.setNewObjectId(id);
                update.setExpectedOldObjectId(ObjectId.zeroId());
                update.setRefLogMessage("snapshot", false);
                RefUpdate.Result result = update.update();
                if (result != RefUpdate.Result.NEW) {
                    throw new GitFailure(branch + " was not created: " + result);
                }
                return id.name();
            }
        });
    }

    /** Work in this repository's git. */
    @FunctionalInterface
    interface GitWork<T> {
        T run(Repository repository) throws IOException;
    }

    /** Opens the repository for the work and closes it after: the tools run once and end. */
    <T> T inGit(GitWork<T> work) {
        try (Repository repository = new FileRepositoryBuilder().setWorkTree(root.toFile()).setMustExist(true).build()) {
            return work.run(repository);
        } catch (IOException e) {
            throw new UncheckedIOException(root.toString(), e);
        }
    }

    /** A git operation that ran and refused: the export tool reports it as invalid use. */
    public static final class GitFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        GitFailure(String message) {
            super(message);
        }

        GitFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
