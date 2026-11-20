// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.personal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.service.secret.PrivateStorage;
import io.github.jaeseongs95.sennard.service.secret.ProcessUser;

/**
 * The personal edition's {@link PrivateStorage}. The personal edition trusts the whole PC: one user, and another OS account of the same PC is not defended against, so nothing here is
 * made to keep one out. What it does:
 * <ul>
 *   <li><b>Creating</b>: a folder or a file is private to its owner from its first instant, with the access it keeps given in the creating call (not narrowed afterwards). A file store
 *       with permission bits ({@code posix} view) gets {@code rwx------} for a folder and {@code rw-------} for a file. A file store with an access list ({@code acl} view, NTFS)
 *       gets a list with no inheritance: full control for the owner (and the system account for a folder). A file store with neither is refused
 *       ({@code PRIVATE_STORAGE_UNSUPPORTED}): the access of what is made cannot be stated there.</li>
 *   <li><b>Reading and checking what exists</b>: the path must be a regular file (a folder where a folder is required) and a secret file must have at least one byte. The permission
 *       bits, the access list, the owner and links are not looked at: a link is followed, and a path that the group, others or another account may use is read.</li>
 * </ul>
 */
public final class LocalPrivateStorage implements PrivateStorage {
    private static final Logger LOG = LoggerFactory.getLogger(LocalPrivateStorage.class);
    private static final String POSIX_VIEW = "posix";
    private static final String ACL_VIEW = "acl";
    private static final List<String> SYSTEM_NAMES = List.of("NT AUTHORITY\\SYSTEM", "SYSTEM");

    private final Supplier<ProcessUser> user;
    /** The principal of the user, once it is known: it is the same for the life of the process. */
    private volatile UserPrincipal resolved;

    /** The storage of the process's own user: where an access list is made, it names the account of the SID in the process's token ({@link ProcessUser}), not a name setting. */
    public LocalPrivateStorage() {
        this(ProcessUser::current);
    }

    /**
     * @param user the user that an access list names as the owner, asked only where an access list is made (a file store with permission bits names no account), so a system that has
     *     no such user never asks
     */
    public LocalPrivateStorage(Supplier<ProcessUser> user) {
        this.user = Objects.requireNonNull(user, "user");
    }

    /** The access list as the attribute that a create call takes. */
    private record AclAttribute(List<AclEntry> value) implements FileAttribute<List<AclEntry>> {
        @Override
        public String name() {
            return "acl:acl";
        }
    }

    private enum Kind { POSIX, ACL }

    @Override
    public void createFolder(Path folder) throws IOException {
        Path absolute = folder.toAbsolutePath();
        switch (kindOf(absolute)) {
            case POSIX -> Files.createDirectories(absolute, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            case ACL -> {
                UserPrincipal owner = ownerOf(absolute);
                List<AclEntry> entries = new ArrayList<>();
                entries.add(fullControl(owner, true));
                system(absolute.getFileSystem()).ifPresent(system -> entries.add(fullControl(system, true)));
                Files.createDirectories(absolute, new AclAttribute(List.copyOf(entries)));
            }
        }
    }

    @Override
    public void createFile(Path file) throws IOException {
        Path absolute = file.toAbsolutePath();
        switch (kindOf(absolute)) {
            case POSIX -> Files.createFile(absolute, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            case ACL -> Files.createFile(absolute, new AclAttribute(List.of(fullControl(ownerOf(absolute), false))));
        }
    }

    @Override
    public void requireSafeFolder(Path folder) throws IOException {
        requireFolder(folder);
    }

    /** The personal edition trusts the PC, so the base that others share is a folder like any other. */
    @Override
    public void requireTrustedFolder(Path folder) throws IOException {
        requireFolder(folder);
    }

    @Override
    public SecretValue readSecret(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        if (!attributes.isRegularFile()) {
            throw refusal(UnavailableCode.PRIVATE_STORAGE_NOT_PLAIN, file);
        }
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) {
            bytes = in.readAllBytes();
        }
        try {
            if (bytes.length == 0) {
                throw refusal(UnavailableCode.PRIVATE_STORAGE_EMPTY, file);
            }
            return SecretValue.ofBytes(bytes);
        } finally {
            SecretValue.clear(bytes);
        }
    }

    private static void requireFolder(Path folder) throws IOException {
        if (!Files.readAttributes(folder, BasicFileAttributes.class).isDirectory()) {
            throw refusal(UnavailableCode.PRIVATE_STORAGE_NOT_PLAIN, folder);
        }
    }

    /** What the file store of the path (or of the nearest folder above it that exists) offers to say who may use a path: permission bits first, then an access list. */
    private static Kind kindOf(Path path) throws IOException {
        Path existing = path;
        while (existing.getParent() != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        FileStore store = Files.getFileStore(existing);
        if (store.supportsFileAttributeView(POSIX_VIEW)) {
            return Kind.POSIX;
        }
        if (store.supportsFileAttributeView(ACL_VIEW)) {
            return Kind.ACL;
        }
        throw refusal(UnavailableCode.PRIVATE_STORAGE_UNSUPPORTED, path);
    }

    private AclEntry fullControl(UserPrincipal principal, boolean inheritable) {
        AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(principal).setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (inheritable) {
            entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        }
        return entry.build();
    }

    /**
     * The principal an access list names as the owner: the user of the process, resolved once ({@link ProcessUser#principal}: the owner of a probe file made in the nearest folder of the
     * path that exists, which is the folder this call is about to create in, else the principal looked up by name and held against the token). The user is not known to the system, or
     * not told apart from another account of its name: PRIVATE_STORAGE_USER_UNKNOWN, and nothing is created.
     */
    private UserPrincipal ownerOf(Path path) throws IOException {
        UserPrincipal known = resolved;
        if (known != null) {
            return known;
        }
        Path folder = path.getParent();
        while (folder != null && !Files.isDirectory(folder)) {
            folder = folder.getParent();
        }
        try {
            if (folder == null) {
                throw new IllegalStateException("No folder of the path exists.");
            }
            UserPrincipal principal = user.get().principal(folder, path.getFileSystem().getUserPrincipalLookupService());
            resolved = principal;
            return principal;
        } catch (IllegalStateException | UserPrincipalNotFoundException unknown) {
            throw new UnavailableException(UnavailableCode.PRIVATE_STORAGE_USER_UNKNOWN, SennardException.args("path", path.toString()), unknown);
        }
    }

    /** The system account, when the machine knows it by one of its English names; a system in another language that does not is the normal case, and the list then names the owner only. */
    private static Optional<UserPrincipal> system(FileSystem fileSystem) throws IOException {
        for (String name : SYSTEM_NAMES) {
            try {
                return Optional.of(fileSystem.getUserPrincipalLookupService().lookupPrincipalByName(name));
            } catch (UserPrincipalNotFoundException notKnown) {
                LOG.debug("The account {} is not known to this machine", name, notKnown);
            }
        }
        return Optional.empty();
    }

    private static UnavailableException refusal(UnavailableCode reason, Path path) {
        return new UnavailableException(reason, SennardException.args("path", path.toString()));
    }
}
