// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.service.personal.LocalPrivateStorage;
import io.github.jaeseongs95.sennard.service.personal.OwnerOnlyAccess;
import io.github.jaeseongs95.sennard.service.secret.PrivateStorage;

/** The start lock keeps its file in the state folder, and the state folder it finds missing is made through the port of the storage of secrets (D-0268). */
class StartLockTest {
    private static final PrivateStorage STORAGE = new LocalPrivateStorage();

    @TempDir
    Path temporary;

    @Test
    void aMissingStateFolderIsMadeByThePortPrivateToItsOwnerAndTheWorkRunsHoldingTheLock() throws Exception {
        Path state = temporary.resolve("not").resolve("made-yet");
        List<String> calls = new ArrayList<>();
        List<String> work = new ArrayList<>();

        boolean ran = StartLock.runHolding(state, recording(calls), () -> work.add("ran"));

        assertTrue(ran);
        assertEquals(List.of("ran"), work);
        assertEquals(List.of("createFolder " + state), calls, "the folder is made by the port and by nothing else");
        assertTrue(Files.isRegularFile(state.resolve(StartLock.FILE)), "the lock's file is in the state folder");
        OwnerOnlyAccess.assertOwnerOnlyFolder(state);
        OwnerOnlyAccess.assertOwnerOnlyFolder(state.getParent());
    }

    @Test
    void aStateFolderThatExistsIsUsedAsItIs() throws Exception {
        Path state = Files.createDirectory(temporary.resolve("state"));
        assertTrue(StartLock.runHolding(state, STORAGE, () -> {
        }));
        assertTrue(Files.isRegularFile(state.resolve(StartLock.FILE)));
    }

    @Test
    void aFolderThePortRefusesIsAStartFailureAndNothingIsMade() {
        Path state = temporary.resolve("refused");
        PrivateStorage refusing = new PrivateStorage() {
            @Override
            public void createFolder(Path folder) throws IOException {
                throw new IOException("the port refuses " + folder);
            }

            @Override
            public void createFile(Path file) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void requireSafeFolder(Path folder) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void requireTrustedFolder(Path folder) {
                throw new UnsupportedOperationException();
            }

            @Override
            public SecretValue readSecret(Path file) {
                throw new UnsupportedOperationException();
            }
        };
        UnavailableException failure = assertThrows(UnavailableException.class, () -> StartLock.runHolding(state, refusing, () -> {
            throw new AssertionError("the work does not run without the lock");
        }));
        assertEquals(UnavailableCode.START_FAILED, failure.code());
        assertTrue(Files.notExists(state), "the folder is made only by the port");
    }

    private static PrivateStorage recording(List<String> calls) {
        return new PrivateStorage() {
            @Override
            public void createFolder(Path folder) throws IOException {
                calls.add("createFolder " + folder);
                STORAGE.createFolder(folder);
            }

            @Override
            public void createFile(Path file) throws IOException {
                STORAGE.createFile(file);
            }

            @Override
            public void requireSafeFolder(Path folder) throws IOException {
                STORAGE.requireSafeFolder(folder);
            }

            @Override
            public void requireTrustedFolder(Path folder) throws IOException {
                STORAGE.requireTrustedFolder(folder);
            }

            @Override
            public SecretValue readSecret(Path file) throws IOException {
                return STORAGE.readSecret(file);
            }
        };
    }
}
