// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.operation;

import java.util.regex.Pattern;

/**
 * The operation of one write call (AGS3-D-0267): the tool, the scope the key belongs to, the operationKey the caller sent, and the digest of the call's content without the key
 * (and without the response mode and the session reference). The adapter of a tool makes it from the call's arguments (it knows the canonical form of the arguments); the use case that
 * does the work takes it to answer a request that came again ({@link OperationReceipts}).
 *
 * <p><b>The scope is the caller.</b> A key is the caller's own: two sessions that send the same key for the same tool are two operations, each with its own effect and its own receipt.
 * The scope is the server-issued reference of the session the server has already settled as the caller of the call (the same session whose rows the tool writes or whose message it
 * sends), never a value the caller sends. Only {@code open_session} has no session yet; its scope is {@link #GLOBAL_SCOPE}, so the same key opens the same session whoever sends it, which
 * is why a key of {@code open_session} is made at random for each call.
 *
 * @param scope the session reference of the caller, or {@link #GLOBAL_SCOPE}
 * @param digest 64 hexadecimal characters
 */
public record OperationKey(String tool, String scope, String key, String digest) {
    /** The common shape of an operationKey, the one of the three products (the SSH approval portal's contract). */
    public static final Pattern SHAPE = Pattern.compile("^[A-Za-z0-9_.:-]{16,128}$");
    /** The name of the argument. */
    public static final String FIELD = "operationKey";
    /** The scope of the one tool that is called before it has a session; a session reference never has this value (it begins with {@code as1_}). */
    public static final String GLOBAL_SCOPE = "global";

    public OperationKey {
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("An operation has a scope: the caller's session reference, or the global scope.");
        }
    }

    public static boolean valid(Object sent) {
        return sent instanceof String text && SHAPE.matcher(text).matches();
    }
}
