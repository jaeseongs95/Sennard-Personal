// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.messaging;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** One immutable incarnation per application context; a restarted server cannot consume the preceding server's nonce. */
@Component
public final class WakeServerIncarnation {
    private final String value = UUID.randomUUID().toString();

    public String value() {
        return value;
    }
}
