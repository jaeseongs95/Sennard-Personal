// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import io.github.jaeseongs95.sennard.server.hook.HostDefinition;
import tools.jackson.databind.json.JsonMapper;

/** Pairs a host's hook definition with its native wake capability in the CLI worker. Register providers with {@link java.util.ServiceLoader}. */
public interface NativeWakeProvider {
    HostDefinition definition();

    NativeWakeAdapter adapter(WakeProcess process, JsonMapper mapper);
}
