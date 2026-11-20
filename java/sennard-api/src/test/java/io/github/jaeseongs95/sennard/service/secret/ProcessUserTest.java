// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.secret;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The user of a Windows process is read from its token: its SID, and the file system's principal of that user, which is the owner of a file the process creates when that is the user
 * (no name is looked up) and otherwise the principal looked up by name and held against the token. The cases that read a token and a real file owner run on Windows.
 */
class ProcessUserTest {
    @TempDir
    Path folder;

    /** The lookup of a principal by name, recording what it is asked and answering with what the test gives. */
    private static final class RecordingLookup extends UserPrincipalLookupService {
        final List<String> asked = new ArrayList<>();
        private final UserPrincipal answer;

        RecordingLookup(UserPrincipal answer) {
            this.answer = answer;
        }

        @Override
        public UserPrincipal lookupPrincipalByName(String name) throws IOException {
            asked.add(name);
            if (answer == null) {
                throw new UserPrincipalNotFoundException(name);
            }
            return answer;
        }

        @Override
        public GroupPrincipal lookupPrincipalByGroupName(String group) {
            throw new UnsupportedOperationException();
        }
    }

    private record Account(String qualifiedName) implements UserPrincipal {
        @Override
        public String getName() {
            return qualifiedName;
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theTokenGivesASidAndTheAccountOfThatSid() {
        ProcessUser user = ProcessUser.current();
        assertTrue(user.sid().startsWith("S-1-"), user.sid());
        assertFalse(user.name().isBlank());
        assertEquals(user, ProcessUser.current(), "the same process reads the same user");
    }

    /** The owner of a new file is the user of the token: it is the answer and no name is looked up (so a name two accounts share, or an account whose name does not resolve, cannot mislead it). */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theOwnerOfANewFileThatIsTheUserIsTheAnswerAndNoNameIsLookedUp() throws Exception {
        RecordingLookup mustNotBeAsked = new RecordingLookup(null);
        UserPrincipal principal = ProcessUser.current().principal(folder, mustNotBeAsked);

        assertNotNull(principal);
        assertEquals(List.of(), mustNotBeAsked.asked, "nothing was looked up by name");
        Path made = Files.createFile(folder.resolve("made"));
        try (var files = Files.list(folder)) {
            assertEquals(1, files.count(), "the probe file is deleted");
        }
        assertEquals(principal.getName(), Files.getOwner(made).getName(), "the principal is the one the system gives the owner of a new file");
    }

    /** An owner that is not the user (a group that owns what an administrator creates) is the case for the name: it is looked up, and the answer is held against the token. */
    @Test
    void anOwnerThatIsNotTheUserSendsTheLookupToTheNameAndTheAnswerThatIsTheUserIsUsed() throws Exception {
        ProcessUser user = new ProcessUser("S-1-5-21-0-0-0-1001", "SOMEDOMAIN", "someone");
        Account theUser = new Account("SOMEDOMAIN\\someone");
        RecordingLookup lookup = new RecordingLookup(theUser);

        UserPrincipal principal = user.principal(folder, lookup);

        assertSame(theUser, principal);
        assertEquals(List.of("someone"), lookup.asked, "the name of the token's account is looked up when the owner is another account");
    }

    @Test
    void whenTheOwnerIsNotTheUserAndTheNameIsAnotherAccountOrUnknownThereIsNoPrincipal() throws Exception {
        ProcessUser user = new ProcessUser("S-1-5-21-0-0-0-1001", "SOMEDOMAIN", "someone");

        RecordingLookup sharedName = new RecordingLookup(new Account("COMPUTER\\someone"));
        assertThrows(IllegalStateException.class, () -> user.principal(folder, sharedName), "the name belongs to a local account, not to the domain account of the token");
        assertEquals(List.of("someone"), sharedName.asked);

        assertThrows(UserPrincipalNotFoundException.class, () -> user.principal(folder, new RecordingLookup(null)));
        try (var files = Files.list(folder)) {
            assertEquals(0, files.count(), "no probe file is left behind");
        }
    }

    /** A principal whose name the system cannot resolve is named by its SID text: that is the token's SID when it is the user. */
    @Test
    void anAccountTheSystemNamesByItsSidIsTheUserWhenTheSidIsTheTokens() throws Exception {
        ProcessUser user = new ProcessUser("S-1-5-21-0-0-0-1001", "", "someone");
        assertSame(Account.class, user.principalByName(new RecordingLookup(new Account("S-1-5-21-0-0-0-1001"))).getClass());
        assertThrows(IllegalStateException.class, () -> user.principalByName(new RecordingLookup(new Account("S-1-5-21-0-0-0-1002"))));
        assertSame(Account.class, new ProcessUser("S-1-5-21-0-0-0-1001", "", "someone").principalByName(new RecordingLookup(new Account("someone"))).getClass(), "no domain: the bare name");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theNameOfTheRealAccountIsFoundByTheFileSystemAndAnotherDomainIsRefused() throws Exception {
        ProcessUser user = ProcessUser.current();
        UserPrincipalLookupService lookup = FileSystems.getDefault().getUserPrincipalLookupService();
        assertNotNull(user.principalByName(lookup), "the file system resolves the account the token reports");
        assertThrows(IllegalStateException.class, () -> new ProcessUser(user.sid(), "NO-SUCH-DOMAIN-0268", user.name()).principalByName(lookup));
        assertThrows(UserPrincipalNotFoundException.class, () -> new ProcessUser(user.sid(), user.domain(), "sennard-no-such-account-0268").principalByName(lookup));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void anotherSystemHasNoTokenAndNoGuessedUser() {
        assertThrows(IllegalStateException.class, ProcessUser::current);
    }
}
