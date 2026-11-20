// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.mcp;

import java.util.Map;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.BindingCode;
import io.github.jaeseongs95.sennard.domain.error.BindingException;

/** The outcome of {@link McpSessionResolver}: a tool that needs a session calls {@link #require()}, one that can work without it switches. */
public sealed interface SessionBinding {
    /** The session this call belongs to. */
    record Bound(SelfSession session) implements SessionBinding {
    }

    /** @param reason why no session could be told, a fixed code */
    record Unbound(String reason) implements SessionBinding {
    }

    /** The session, or {@code BINDING_REQUIRED} with the reason in its details. */
    default SelfSession require() {
        return switch (this) {
            case Bound bound -> bound.session();
            case Unbound unbound -> throw new BindingException(BindingCode.BINDING_REQUIRED, Map.of(), SennardException.args("reasonCode", unbound.reason()));
        };
    }
}
