// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.secret;

import java.io.IOException;
import java.nio.file.Path;

import io.github.jaeseongs95.sennard.domain.secret.SecretValue;

/**
 * Where the folders and files that hold a secret (the state folder, the key and pepper files, the database password file, the settings database folder, the substitute folder of an
 * installation) are made and read: the one port every one of them goes through, so that no module makes or reads such a path by itself. An edition implements it (the personal
 * edition: {@code LocalPrivateStorage} of sennard-personal) and its configuration registers the bean; a caller takes this interface and nothing of an edition.
 *
 * <p>What every implementation keeps: a path that this port creates is private to its owner from its first instant (it is created with the access it keeps, not narrowed
 * afterwards), a path that is not what the caller needs (a folder where a file is needed, a file where a folder is needed, an empty secret file) is refused, and a refusal is
 * an {@link io.github.jaeseongs95.sennard.domain.error.UnavailableException} of a {@code PRIVATE_STORAGE_*} code (or an {@link IOException} for a file that cannot be read) that names the path
 * and never the content. What an implementation does about the other accounts of the machine is the edition's trust range and is not part of this contract.
 */
public interface PrivateStorage {

    /** Creates the folder, with its missing parents, private to its owner; a folder that exists is left as it is. */
    void createFolder(Path folder) throws IOException;

    /** Creates the empty file, private to its owner from its first instant, failing when it exists. */
    void createFile(Path file) throws IOException;

    /** The path must be a folder the caller may use. */
    void requireSafeFolder(Path folder) throws IOException;

    /** The path must be a folder that other users may share too (there is no substitute folder: every folder is the one the user's own state folder names) and the caller may use. */
    void requireTrustedFolder(Path folder) throws IOException;

    /**
     * The bytes of a secret file, which must be a regular file with at least one byte; the one way a secret file is read. A path that is not a regular file is PRIVATE_STORAGE_NOT_PLAIN and
     * an empty file is PRIVATE_STORAGE_EMPTY (an {@link io.github.jaeseongs95.sennard.domain.error.UnavailableException}): the caller decides from the code and never looks at the file again. A file
     * that is not there or cannot be read is an {@link IOException}.
     */
    SecretValue readSecret(Path file) throws IOException;
}
