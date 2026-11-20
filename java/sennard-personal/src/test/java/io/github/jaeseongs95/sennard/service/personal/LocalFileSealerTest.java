// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.personal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.service.secret.SealedValue;
import io.github.jaeseongs95.sennard.service.secret.SecretName;
import io.github.jaeseongs95.sennard.service.secret.SecretSealException;

class LocalFileSealerTest {
    private static final SecretName NAME = new SecretName("jev.openai");
    private static final Map<String, String> AAD = Map.of("scope", "pc", "scope_id", "local", "fingerprint", "sha256:abc");

    /** The sealer with the process's random source, which the product takes as a bean. */
    private static LocalFileSealer sealerAt(Path file) {
        return new LocalFileSealer(file, new SecureRandom(), new LocalPrivateStorage());
    }

    /** JUnit deletes this folder, and everything the test put in it, after each test (D-0194). */
    @TempDir
    private Path folder;

    /** A key file in a folder of its own: a test that needs two keys calls this twice. */
    private Path keyFile() throws Exception {
        return Files.createTempDirectory(folder, "seal-").resolve("service").resolve("data.key");
    }

    @Test
    void aValueOpensUnderTheSameNameAndDataAndNotOtherwise() throws Exception {
        LocalFileSealer sealer = sealerAt(keyFile());
        byte[] secret = "sk-test-secret-value".getBytes(StandardCharsets.UTF_8);
        SealedValue sealed = sealer.seal(NAME, secret, AAD);
        assertFalse(sealed.text().contains("sk-test"));
        assertArrayEquals(secret, sealer.open(sealed, NAME, AAD));
        assertEquals("SECRET_UNREADABLE", assertThrows(SecretSealException.class, () -> sealer.open(sealed, new SecretName("jev.other"), AAD)).code());
        assertEquals("SECRET_UNREADABLE", assertThrows(SecretSealException.class, () -> sealer.open(sealed, NAME, Map.of("scope", "pc", "scope_id", "other", "fingerprint", "sha256:abc"))).code());
        assertEquals("SECRET_UNREADABLE", assertThrows(SecretSealException.class, () -> sealer.open(sealed, NAME, Map.of("scope", "pc"))).code());
    }

    @Test
    void everySealUsesAFreshIvSoTheSameValueSealsDifferently() throws Exception {
        LocalFileSealer sealer = sealerAt(keyFile());
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            texts.add(sealer.seal(NAME, "same".getBytes(StandardCharsets.UTF_8), AAD).text());
        }
        assertEquals(20, texts.stream().distinct().count());
        assertEquals(20, texts.stream().map(t -> t.split("\\.")[1]).distinct().count(), "twenty different IVs");
        assertEquals(12, Base64.getUrlDecoder().decode(texts.get(0).split("\\.")[1]).length, "96-bit IV");
    }

    @Test
    void aTamperedOrForeignValueDoesNotOpen() throws Exception {
        LocalFileSealer sealer = sealerAt(keyFile());
        String sealed = sealer.seal(NAME, "value".getBytes(StandardCharsets.UTF_8), AAD).text();
        String[] parts = sealed.split("\\.");
        byte[] body = Base64.getUrlDecoder().decode(parts[2]);
        body[0] ^= 1;
        String flipped = parts[0] + "." + parts[1] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(body);
        assertEquals("SECRET_UNREADABLE", assertThrows(SecretSealException.class, () -> sealer.open(new SealedValue(flipped), NAME, AAD)).code());
        for (String bad : new String[] {"v2." + parts[1] + "." + parts[2], "v1.only", "garbage", "v1..", parts[0] + ".!!!." + parts[2]}) {
            assertEquals("SECRET_UNREADABLE", assertThrows(SecretSealException.class, () -> sealer.open(new SealedValue(bad), NAME, AAD)).code(), bad);
        }
        LocalFileSealer otherKey = sealerAt(keyFile());
        otherKey.seal(NAME, new byte[] {1}, AAD);
        assertEquals("SECRET_UNREADABLE", assertThrows(SecretSealException.class, () -> otherKey.open(new SealedValue(sealed), NAME, AAD)).code(),
                "a value sealed under one key file does not open under another");
    }

    @Test
    void theDataKeyIsMadeOnFirstSealAndKeptOwnerOnlyAndNeverCreatedByAnOpen() throws Exception {
        Path file = keyFile();
        LocalFileSealer sealer = sealerAt(file);
        SealedValue early = new SealedValue("v1.AAAAAAAAAAAAAAAA.AAAAAAAAAAAAAAAAAAAAAA");
        assertEquals("SECRET_KEY_UNAVAILABLE", assertThrows(SecretSealException.class, () -> sealer.open(early, NAME, AAD)).code());
        assertFalse(Files.exists(file), "opening never creates a key");
        sealer.seal(NAME, new byte[] {1}, AAD);
        assertEquals(32, Files.size(file));
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }
        byte[] key = Files.readAllBytes(file);
        sealer.seal(NAME, new byte[] {2}, AAD);
        assertArrayEquals(key, Files.readAllBytes(file), "the key is not replaced by later seals");
        try (var siblings = Files.list(file.getParent())) {
            assertEquals(1, siblings.count(), "no temporary key file is left");
        }
        Files.write(file, new byte[5]);
        assertEquals("SECRET_KEY_UNAVAILABLE", assertThrows(SecretSealException.class, () -> sealerAt(file).seal(NAME, new byte[] {1}, AAD)).code());
    }

    /** An empty key file is the port's PRIVATE_STORAGE_EMPTY, and the sealer answers it as it answers a key of the wrong size: the key is unavailable, and it is never replaced. */
    @Test
    void anEmptyKeyFileIsAKeyUnavailableAndIsNeverReplaced() throws Exception {
        Path file = keyFile();
        sealerAt(file).seal(NAME, new byte[] {1}, AAD);
        Files.write(file, new byte[0]);
        SecretSealException onSeal = assertThrows(SecretSealException.class, () -> sealerAt(file).seal(NAME, new byte[] {1}, AAD));
        assertEquals("SECRET_KEY_UNAVAILABLE", onSeal.code());
        assertEquals(io.github.jaeseongs95.sennard.domain.error.UnavailableCode.PRIVATE_STORAGE_EMPTY,
                assertInstanceOf(io.github.jaeseongs95.sennard.domain.error.UnavailableException.class, onSeal.getCause()).code(), "the port's code is the cause");
        assertEquals(0, Files.size(file), "an empty key file is left for its owner to restore");
    }

    /**
     * The key file must already be owner-only when its 32 bytes arrive. The fixture records the real file at the moment of
     * each write, so a creation that writes first and restricts afterwards (the window in question) fails here on every platform.
     */
    @Test
    void theKeyFileIsOwnerOnlyBeforeAnyOfItsBytesAreWritten() throws Exception {
        WriteObservingFileSystem observing = new WriteObservingFileSystem(FileSystems.getDefault());
        Path file = observing.wrap(Files.createTempDirectory(folder, "seal-")).resolve("service").resolve("data.key");
        LocalFileSealer sealer = sealerAt(file);
        sealer.seal(NAME, new byte[] {1}, AAD);

        List<WriteObservingFileSystem.Observation> writes = observing.observations();
        assertEquals(1, writes.size(), "one write of the key: " + writes);
        WriteObservingFileSystem.Observation write = writes.get(0);
        assertEquals(0, write.sizeBefore(), "nothing was in the file before the owner-only state: " + write);
        assertTrue(write.ownerOnly(), "the key file allows others when its bytes are written: " + write.access());
    }

    /** The finished key file holds no entry for anyone but its owner, so a copy of the state folder or another user of the PC cannot read it. */
    @Test
    void theFinishedKeyFileAllowsItsOwnerAndNobodyElse() throws Exception {
        Path file = keyFile();
        sealerAt(file).seal(NAME, new byte[] {1}, AAD);
        assertTrue(WriteObservingFileSystem.ownerOnly(file), WriteObservingFileSystem.describeAccess(file));
    }

    @Test
    void processesThatRaceToCreateTheKeyEndWithOneKeyAndEveryValueOpens() throws Exception {
        Path file = keyFile();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> work = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                work.add(() -> sealerAt(file).seal(NAME, "v".getBytes(StandardCharsets.UTF_8), AAD).text());
            }
            List<Future<String>> futures = pool.invokeAll(work);
            LocalFileSealer reader = sealerAt(file);
            for (Future<String> future : futures) {
                assertArrayEquals("v".getBytes(StandardCharsets.UTF_8), reader.open(new SealedValue(future.get()), NAME, AAD));
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(32, Files.size(file));
    }

    /** The personal edition trusts the PC: a key file that was opened to other accounts after its creation is read as it is (and is not repaired). */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aKeyFileThatOthersMayReadIsStillReadAndNotRepaired() throws Exception {
        Path file = keyFile();
        LocalFileSealer sealer = sealerAt(file);
        SealedValue sealed = sealer.seal(NAME, "value".getBytes(StandardCharsets.UTF_8), AAD);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        assertArrayEquals("value".getBytes(StandardCharsets.UTF_8), sealer.open(sealed, NAME, AAD));
        assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)), "nothing is repaired");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aKeyFileThatIsASymbolicLinkIsFollowed() throws Exception {
        Path file = keyFile();
        SealedValue sealed = sealerAt(file).seal(NAME, "value".getBytes(StandardCharsets.UTF_8), AAD);
        Path link = file.resolveSibling("link.key");
        Files.createSymbolicLink(link, file);
        assertArrayEquals("value".getBytes(StandardCharsets.UTF_8), sealerAt(link).open(sealed, NAME, AAD));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aKeyFileThatAnotherAccountMayReadIsStillRead() throws Exception {
        Path file = keyFile();
        LocalFileSealer sealer = sealerAt(file);
        SealedValue sealed = sealer.seal(NAME, "value".getBytes(StandardCharsets.UTF_8), AAD);
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        List<AclEntry> widened = new ArrayList<>(acl.getAcl());
        UserPrincipal users = file.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("BUILTIN\\Users");
        widened.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(users).setPermissions(EnumSet.of(AclEntryPermission.READ_DATA)).build());
        acl.setAcl(widened);
        assertArrayEquals("value".getBytes(StandardCharsets.UTF_8), sealer.open(sealed, NAME, AAD));
    }

    @Test
    void nothingOfTheKeyIsExposed() {
        assertTrue(java.util.Arrays.stream(LocalFileSealer.class.getMethods()).noneMatch(m -> m.getReturnType() == byte[].class && !m.getName().equals("open")),
                "no method hands out bytes except opening a sealed value");
    }
}
