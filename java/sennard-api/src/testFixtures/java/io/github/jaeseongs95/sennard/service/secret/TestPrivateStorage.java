// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.secret;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.domain.secret.SecretValue;

/**
 * The one stand-in of the port {@link PrivateStorage} for the tests of the modules that have no edition (sennard-app, sennard-settings, sennard-store-pg; sennard-personal, whose edition is the real
 * {@code LocalPrivateStorage}, tests that one). It keeps what every implementation keeps of the contract, with the same codes as the personal edition: a path that is not a regular file
 * where a secret file is read, or not a folder where a folder is required, is PRIVATE_STORAGE_NOT_PLAIN; an empty secret file is PRIVATE_STORAGE_EMPTY; a file that exists is never
 * created again; the bytes read are copied into the value and the copy this class made is overwritten. What it does not do is the edition's own: it gives what it creates the default access of
 * the file system (the access that a real edition gives is tested where the edition is, in sennard-personal's {@code LocalPrivateStorageTest}). It records the calls it takes, so that a test can
 * show that a folder or a file was made by the port.
 */
public final class TestPrivateStorage implements PrivateStorage {
    private final List<String> calls = new CopyOnWriteArrayList<>();

    /** The calls taken so far, in order, as {@code createFolder <path>}, {@code createFile <path>}, {@code requireSafeFolder <path>}, {@code requireTrustedFolder <path>}, {@code readSecret <path>}. */
    public List<String> calls() {
        return List.copyOf(calls);
    }

    @Override
    public void createFolder(Path folder) throws IOException {
        calls.add("createFolder " + folder);
        Files.createDirectories(folder);
    }

    @Override
    public void createFile(Path file) throws IOException {
        calls.add("createFile " + file);
        Files.createFile(file);
    }

    @Override
    public void requireSafeFolder(Path folder) throws IOException {
        calls.add("requireSafeFolder " + folder);
        requireFolder(folder);
    }

    @Override
    public void requireTrustedFolder(Path folder) throws IOException {
        calls.add("requireTrustedFolder " + folder);
        requireFolder(folder);
    }

    @Override
    public SecretValue readSecret(Path file) throws IOException {
        calls.add("readSecret " + file);
        if (!Files.readAttributes(file, BasicFileAttributes.class).isRegularFile()) {
            throw refusal(UnavailableCode.PRIVATE_STORAGE_NOT_PLAIN, file);
        }
        byte[] bytes = Files.readAllBytes(file);
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

    private static UnavailableException refusal(UnavailableCode reason, Path path) {
        return new UnavailableException(reason, SennardException.args("path", path.toString()));
    }
}
