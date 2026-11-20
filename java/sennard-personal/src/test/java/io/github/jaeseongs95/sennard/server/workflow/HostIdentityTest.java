// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.service.personal.LocalPrivateStorage;
import io.github.jaeseongs95.sennard.service.personal.OwnerOnlyAccess;
import io.github.jaeseongs95.sennard.service.secret.PrivateStorage;

/**
 * The machine's identity is one random UUID made once and kept in the state folder (install time), never read from the operating system: it has the UUID form, it is
 * the same after a restart and after a reinstall (the state folder stays), and two state folders have two identities.
 */
class HostIdentityTest {
    /** The personal edition's storage of secrets, which makes the state folder. */
    private static final PrivateStorage STORAGE = new LocalPrivateStorage();

    @TempDir
    Path state;

    private static String read(Path stateDirectory) throws IOException {
        return Files.readString(stateDirectory.resolve(HostIdentity.FILE), StandardCharsets.UTF_8).strip();
    }

    @Test
    void theInstallMakesOneUuidAndTheServerReadsTheSameOne() throws IOException {
        String made = HostIdentity.ensure(state, STORAGE);
        assertEquals(UUID.fromString(made).toString(), made, "the canonical UUID text");
        assertEquals(made, read(state));
        assertEquals(made, new HostIdentity(state, STORAGE).id());
    }

    /** The state folder of an install that finds none is made by the port, private to its owner from its first instant (D-0268), and the identity is kept in it. */
    @Test
    void aStateFolderThatIsNotThereIsMadeThroughThePortAndPrivateToItsOwner() throws Exception {
        Path missing = state.resolve("not").resolve("made-yet");
        List<String> calls = new ArrayList<>();
        PrivateStorage recording = new PrivateStorage() {
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

        String id = HostIdentity.ensure(missing, recording);

        assertEquals(List.of("createFolder " + missing), calls, "the folder is made by the port and by nothing else");
        assertEquals(id, read(missing));
        OwnerOnlyAccess.assertOwnerOnlyFolder(missing);
        OwnerOnlyAccess.assertOwnerOnlyFolder(missing.getParent());
    }

    /** The server that starts with no state folder and no install (the component) makes the folder the same way. */
    @Test
    void theComponentMakesAMissingStateFolderThroughThePortToo() throws Exception {
        Path missing = state.resolve("server-made");
        String id = new HostIdentity(missing, STORAGE).id();
        assertEquals(id, read(missing));
        OwnerOnlyAccess.assertOwnerOnlyFolder(missing);
    }

    /** A port that refuses the folder is an identity that cannot be resolved, not a raw refusal of the storage. */
    @Test
    void aFolderThePortRefusesLeavesTheIdentityUnresolved() throws Exception {
        Path never = state.resolve("never");
        PrivateStorage refusing = new PrivateStorage() {
            @Override
            public void createFolder(Path folder) {
                throw new UnavailableException(UnavailableCode.PRIVATE_STORAGE_UNSUPPORTED, SennardException.args("path", folder.toString()));
            }

            @Override
            public void createFile(Path path) {
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
            public SecretValue readSecret(Path path) {
                throw new UnsupportedOperationException();
            }
        };
        WorkflowFailure failure = assertThrows(WorkflowFailure.class, () -> new HostIdentity(never, refusing).id());
        assertEquals("HOST_IDENTITY_UNRESOLVED", failure.details().get("reason"));
        assertFalse(Files.exists(never), "nothing is made behind the port's back");
    }

    @Test
    void theServerMakesTheIdentityWhenNoInstallDid() throws IOException {
        String id = new HostIdentity(state, STORAGE).id();
        assertEquals(UUID.fromString(id).toString(), id);
        assertEquals(id, read(state));
    }

    @Test
    void aRestartAndAReinstallKeepTheIdentity() throws IOException {
        String first = HostIdentity.ensure(state, STORAGE);
        assertEquals(first, new HostIdentity(state, STORAGE).id(), "a restart");
        assertEquals(first, HostIdentity.ensure(state, STORAGE), "an install over a state folder that stayed");
        assertEquals(first, read(state));
    }

    @Test
    void twoStateFoldersAreTwoMachines() throws IOException {
        Path other = Files.createDirectory(state.resolve("other"));
        assertNotEquals(HostIdentity.ensure(state, STORAGE), HostIdentity.ensure(other, STORAGE));
    }

    @Test
    void aFileThatIsNotAUuidIsNeverReplaced() throws IOException {
        Files.writeString(state.resolve(HostIdentity.FILE), "not-a-uuid\n", StandardCharsets.UTF_8);
        assertNull(HostIdentity.ensure(state, STORAGE));
        WorkflowFailure failure = assertThrows(WorkflowFailure.class, () -> new HostIdentity(state, STORAGE).id());
        assertEquals("HOST_IDENTITY_UNRESOLVED", failure.details().get("reason"));
        assertEquals("not-a-uuid", read(state), "the file is left as it was");
    }

    @Test
    void callersThatStartTogetherAgreeOnOneIdentity() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> callers = java.util.stream.IntStream.range(0, 16).<Callable<String>>mapToObj(i -> () -> HostIdentity.ensure(state, STORAGE)).toList();
            List<String> answers = pool.invokeAll(callers).stream().map(HostIdentityTest::get).toList();
            assertEquals(1, answers.stream().distinct().count(), answers.toString());
            assertTrue(answers.stream().allMatch(answer -> answer != null && answer.equals(answers.getFirst())));
        } finally {
            pool.shutdownNow();
        }
    }

    private static String get(Future<String> future) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", interrupted);
        } catch (java.util.concurrent.ExecutionException failed) {
            throw new IllegalStateException(failed.getCause());
        }
    }
}
