// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.admin;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Component;

/**
 * The nonce of a page's Content-Security-Policy: 18 random bytes from a {@link SecureRandom}, standard Base64. A nonce a script could guess would let a script that is
 * not the page's own run, so it is not drawn from a generator meant for speed. One bean holds the generator for both the page and the redirect page.
 */
@Component
public class CspNonce {
    private static final int BYTES = 18;

    private final SecureRandom random;

    /** @param random the application's one {@link SecureRandom} (the bean of {@code RandomConfiguration}) */
    public CspNonce(SecureRandom random) {
        this.random = random;
    }

    public String next() {
        byte[] bytes = new byte[BYTES];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
