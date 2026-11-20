// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.file.Path;

import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.service.secret.PrivateStorage;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;

/**
 * Reads a password from the file a config points at: the first line, trimmed. The file is read through the port of the storage of secrets ({@link PrivateStorage}), which refuses
 * what is not a regular file and an empty file; what the edition does about the access of the file is the edition's own. The value is a {@link SecretValue}, so it is never logged
 * or put in a URL.
 */
public final class Credentials {
    private Credentials() {
    }

    public static SecretValue read(Path file, PrivateStorage storages) {
        char[] text;
        try {
            // The value read from the file is an intermediate: the characters are taken from it and it is destroyed at once.
            SecretValue stored = storages.readSecret(file);
            try {
                text = stored.reveal();
            } finally {
                stored.destroy();
            }
        } catch (IOException cause) {
            throw new StoreException(StoreCode.DB_CREDENTIAL_UNREADABLE, "A database credential file cannot be read.", cause);
        } catch (UnavailableException refusal) {
            if (refusal.code() != UnavailableCode.PRIVATE_STORAGE_EMPTY) {
                throw refusal; // a path that is not a regular file is the port's refusal, named by its own code
            }
            throw new StoreException(StoreCode.DB_CREDENTIAL_UNREADABLE, "A database credential file is empty.", refusal);
        }
        try {
            int end = 0;
            while (end < text.length && text[end] != '\n') {
                end++;
            }
            int from = 0;
            while (from < end && text[from] <= ' ') {
                from++;
            }
            int to = end;
            while (to > from && text[to - 1] <= ' ') {
                to--;
            }
            if (from == to) {
                throw new StoreException(StoreCode.DB_CREDENTIAL_UNREADABLE, "A database credential file is empty.");
            }
            return SecretValue.of(CharBuffer.wrap(text, from, to - from));
        } finally {
            SecretValue.clear(text);
        }
    }
}
