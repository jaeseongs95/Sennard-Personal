// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.secret;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;

import com.sun.security.auth.module.NTSystem;

/**
 * The user a Windows process runs as, read from the process's own access token (the JDK's reading of it, {@link NTSystem}) and never from a name setting such as {@code user.name}: a
 * name is not an identity (a local account and a domain account may share one, and a name that an Azure AD sign-in shows may not resolve), the token's SID is. The one helper that
 * every module that has to name the user of the process goes through: the access list of a folder or a
 * file that is private to its owner names {@link #principal}.
 *
 * <p>The JDK cannot make a file system principal from a SID text ({@code lookupPrincipalByName("S-1-5-...")} is not found) and has no public way to read the SID of a principal. What it
 * does give is the owner of a file ({@link Files#getOwner}), a principal that the system made from the SID of the owner, with no name looked up. {@link #principal} therefore takes
 * what the system does with a file this process creates:
 * <ol>
 *   <li>It creates an empty probe file in a folder that exists, reads its owner and deletes it. The new file's owner is the token's default owner, which is the user. When that principal
 *       is the user of the token, it is the answer: the system gave it from the SID and nothing was looked up by name, so a local and a domain account of one name and an Azure AD
 *       account are told apart. "Is the user of the token" is decided by the principal's own name, which the system derives from the SID ({@link UserPrincipal#getName()}: the account
 *       {@code domain} and {@code name}, or the SID text when the system cannot resolve it) against the domain and the name Windows reports for the token's SID, which {@link NTSystem}
 *       derives from the same SID in the same way. Two accounts have one qualified name only when they are one account.</li>
 *   <li>A process that runs with administrator rights may have a group (Administrators) as the default owner of what it creates. Only then is the name looked up
 *       ({@link #principalByName}), and the answer is held against the token the same way: an answer that is another account (a name that the local account shares with the domain
 *       account the process runs as) is refused and never used.</li>
 * </ol>
 * No account name is composed here, and no access list is made for an account that is not the user of the token.
 *
 * <p>This reads a Windows token: on any other system {@link #current()} fails with an {@link IllegalStateException}, and nothing there needs it (permission bits name no principal).
 *
 * @param sid the user's SID text ({@code S-1-5-21-...})
 * @param domain the domain of that SID's account as Windows reports it (the computer's name for a local account); empty when Windows reports none
 * @param name the name of that SID's account without its domain
 */
public record ProcessUser(String sid, String domain, String name) {
    private static final String SID_PREFIX = "S-1-";
    /** The one character that stands between the domain and the name in the name a Windows file system gives a principal. */
    private static final int ACCOUNT_SEPARATOR_LENGTH = 1;

    /**
     * The user of this process, read from its token.
     *
     * @throws IllegalStateException when the token cannot be read (not Windows, or no SID): there is no guessed name
     */
    public static ProcessUser current() {
        try {
            NTSystem token = new NTSystem();
            String sid = token.getUserSID();
            String name = token.getName();
            String domain = token.getDomain();
            if (sid == null || !sid.startsWith(SID_PREFIX) || name == null || name.isBlank()) {
                throw new IllegalStateException("The user of the process cannot be read from its token.");
            }
            return new ProcessUser(sid, domain == null ? "" : domain, name);
        } catch (RuntimeException | LinkageError cause) {
            if (cause instanceof IllegalStateException unreadable) {
                throw unreadable;
            }
            throw new IllegalStateException("The user of the process cannot be read from its token.", cause);
        }
    }

    /**
     * The file system's principal of this user, for an access list: the owner of a file this process creates when that is the user of the token, else the principal looked up by name
     * and held against the token.
     *
     * @param folder a folder that exists and that this process may create a file in (the probe is made in it and deleted at once; it is the folder the caller is about to create in)
     * @param byName the file system's lookup of a principal by name, used only when the owner of a new file is not the user of the token
     * @throws java.nio.file.attribute.UserPrincipalNotFoundException when the owner is not the user and the system knows no account of the name
     * @throws IllegalStateException when the owner is not the user and the account the system found by name is not the account of the token
     */
    public UserPrincipal principal(Path folder, UserPrincipalLookupService byName) throws IOException {
        Path probe = Files.createTempFile(folder, "owner-", ".tmp");
        UserPrincipal owner;
        try {
            owner = Files.getOwner(probe);
        } finally {
            Files.deleteIfExists(probe);
        }
        if (isTheUser(owner)) {
            return owner;
        }
        return principalByName(byName);
    }

    /**
     * The principal looked up by the name of the token's account, held against the token.
     *
     * @throws java.nio.file.attribute.UserPrincipalNotFoundException when the system knows no account of that name
     * @throws IllegalStateException when the account the system found is not the account of the token
     */
    public UserPrincipal principalByName(UserPrincipalLookupService byName) throws IOException {
        UserPrincipal found = byName.lookupPrincipalByName(name);
        if (!isTheUser(found)) {
            throw new IllegalStateException("The name of the user of the process belongs to another account in this system.");
        }
        return found;
    }

    /** Whether the principal is the account of the token: the name the system gives it is the token's domain and name, or the token's SID when the system cannot resolve the account. */
    private boolean isTheUser(UserPrincipal principal) {
        String reported = principal.getName();
        if (reported.equals(sid)) {
            return true;
        }
        int domainLength = domain.isEmpty() ? 0 : domain.length() + ACCOUNT_SEPARATOR_LENGTH;
        return reported.length() == domainLength + name.length()
                && reported.regionMatches(true, 0, domain, 0, domain.length())
                && reported.regionMatches(true, domainLength, name, 0, name.length());
    }
}
