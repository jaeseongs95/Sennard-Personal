// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.service.secret.PrivateStorage;

/**
 * The folders and files the attachments live in (the design's 4.2, AGS3-D-0268). Every folder and file is made by the edition's {@link PrivateStorage}, which gives what it makes its
 * access from the first instant (the creation of a secret file and the read of one are that port's, not this module's). Nothing here judges who owns a folder or who may read it:
 * a folder or file that exists is used as it is, and the only refusal of this class is a path that is not what the design says it is (a regular file where a key file belongs, a
 * folder where a folder belongs): ARTIFACT_STORAGE_UNSAFE, which has no other meaning. A refusal of the port is put in the attachments' own codes by
 * {@link ArtifactErrors#privateStorage}.
 */
@Component
public class StorageFolders {
    private final PrivateStorage storage;

    public StorageFolders(PrivateStorage storage) {
        this.storage = storage;
    }

    /** The folder exists; the port makes it (with its parents) when it is not there. A path that is there and is not a folder is ARTIFACT_STORAGE_UNSAFE. */
    public void ensureOwnerOnlyFolder(Path folder) {
        try {
            if (!Files.exists(folder)) {
                storage.createFolder(folder);
            }
            storage.requireSafeFolder(folder);
        } catch (UnavailableException refusal) {
            throw ArtifactErrors.privateStorage(refusal, "not-a-folder");
        } catch (IOException cause) {
            throw ArtifactErrors.storageIo(cause);
        }
    }

    /** Makes a subfolder of an attachment folder through the port; a path that is there and is not a folder is ARTIFACT_STORAGE_UNSAFE. */
    public void ensureSubfolder(Path folder) {
        ensureOwnerOnlyFolder(folder);
    }

    /** Has the port create the file, private from its first instant, failing when it exists ({@link java.nio.file.FileAlreadyExistsException}). */
    public void createOwnerOnlyFile(Path file) throws IOException {
        try {
            storage.createFile(file);
        } catch (UnavailableException refusal) {
            throw ArtifactErrors.privateStorage(refusal, "not-a-file");
        }
    }
}
