// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

import java.util.Optional;

/** A protocol input classified before any native event consumer. An intercepted event never reaches the ordinary domains. */
public interface HookBoundary {
    default boolean bootstrap(HostEvent event, HookVerdict verdict, String wakeKey) {
        return event instanceof HostEvent.SessionStarted started && bootstrap(started, wakeKey);
    }

    default boolean bootstrap(HostEvent.SessionStarted event, String wakeKey) {
        return false;
    }
    Optional<HookVerdict> before(HostEvent event);
    default void after(HostEvent event, HookVerdict verdict) {
    }
}
